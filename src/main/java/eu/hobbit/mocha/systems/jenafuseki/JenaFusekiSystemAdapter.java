package eu.hobbit.mocha.systems.jenafuseki;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.FileUtils;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;
import org.apache.jena.shared.Lock;
import org.apache.jena.shared.LockMRSW;
import org.apache.jena.system.Txn;
import org.hobbit.core.components.AbstractSystemAdapter;
import org.hobbit.core.components.AbstractSystemAdapter1;
import org.hobbit.core.rabbit.RabbitMQUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.hobbit.mocha.systems.jenafuseki.util.Constants;

/**
 * Apache Jena Fuseki System Adapter class for all MOCHA tasks
 * 
 * @author Vassilis Papakonstantinou (papv@ics.forth.gr)
 */

public class JenaFusekiSystemAdapter extends AbstractSystemAdapter1 {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(JenaFusekiSystemAdapter.class);
		
	private boolean dataLoadingFinished = false;
	
	private AtomicInteger totalReceived = new AtomicInteger(0);
	private AtomicInteger totalSent = new AtomicInteger(0);
	
	private AtomicInteger totalDGInserts = new AtomicInteger(0);
	private AtomicInteger totalDGInsertsExecuted = new AtomicInteger(0);
	private AtomicInteger totalTGSelects = new AtomicInteger(0);
	private AtomicInteger totalTGSelectsExecuted = new AtomicInteger(0);
	
	private Semaphore allDataReceivedMutex = new Semaphore(0);
	private Semaphore fusekiServerStartedMutex = new Semaphore(0);
	private Semaphore allTaskExecutedOrTimeoutMutex = new Semaphore(0);
	
	private ExecutorService executor = Executors.newFixedThreadPool(32);
	private LockMRSW lock = new LockMRSW();
	
	private int loadingNumber = 0;
	private AtomicBoolean fusekiServerStarted = new AtomicBoolean(false);
	private String datasetFolderName = "/myvol/datasets";
	
	private RDFConnection conn;
		
	public JenaFusekiSystemAdapter(int numberOfMessagesInParallel) {
		super(numberOfMessagesInParallel);
	}
	
	public JenaFusekiSystemAdapter() { }
	
	public void init() throws Exception {
		LOGGER.info("Initialization begins.");
		conn = RDFConnectionFactory.connect("http://localhost:3030/ds");
		super.init();
		LOGGER.info("Initialization is over.");
	}
	
	public void receiveGeneratedData(byte[] data) {
		if (dataLoadingFinished == false) {
			ByteBuffer dataBuffer = ByteBuffer.wrap(data);    	
			String fileName = RabbitMQUtils.readString(dataBuffer);
			
			LOGGER.info("Receiving file: " + fileName);
						
			byte [] content = new byte[dataBuffer.remaining()];
			dataBuffer.get(content, 0, dataBuffer.remaining());
			
			if (content.length != 0) {
				try {
					if (fileName.contains("/"))
						fileName = fileName.replaceAll("[^/]*[/]", "");
					FileUtils.writeByteArrayToFile(new File(datasetFolderName + File.separator + fileName), content, true);
				} catch (FileNotFoundException e) {
					LOGGER.error("Exception while writing data file", e);
				} catch (IOException e) {
					LOGGER.error("Exception while writing data file", e);
				}
			}

			if(totalReceived.incrementAndGet() == totalSent.get()) {
				allDataReceivedMutex.release();
			}
		}
		else {			
			ByteBuffer buffer = ByteBuffer.wrap(data);
			String insertQuery = RabbitMQUtils.readString(buffer); 
			
			// rewrite insert to let jena fuseki to create the appropriate graphs while inserting
			insertQuery = insertQuery.replaceFirst("INSERT", "").replaceFirst("WITH", "INSERT DATA { GRAPH");
			insertQuery = insertQuery.substring(0, insertQuery.length() - 13).concat(" }");
			String rewrittenInsertQuery = insertQuery;
			
			int currInsertCount = totalDGInserts.incrementAndGet();
			executor.submit(() -> {
				lock.enterCriticalSection(Lock.WRITE);
				try {
					Txn.executeWrite(conn, () -> conn.update(rewrittenInsertQuery));
					float f = (float) totalDGInsertsExecuted.incrementAndGet() / totalDGInserts.get() * 100;
					LOGGER.info(new DecimalFormat("#.##").format(f) + "% - INSERT " + currInsertCount + " of " + totalDGInserts.get() + " executed successfully.");
				} finally {
					lock.leaveCriticalSection();
				}
			});
//			LOGGER.info("INSERT query (" + currInsertCount + ") received from data generator and submitted for execution.");
		}
	}

	public void receiveGeneratedTask(String taskId, byte[] data) {
		// before executing a task check if Fuseki Server is up and running and if not wait until it is
		if(!fusekiServerStarted.get()) {
			LOGGER.info("[Task] Waiting until Jena Fuseki Server is online...");
			try {
				fusekiServerStartedMutex.acquire();
			} catch (InterruptedException e) {
				LOGGER.error("Exception while waitting for Fuseki Server to be started.", e);
			}
			LOGGER.info("Jena Fuseki Server started successfully.");
		}
		
		ByteBuffer buffer = ByteBuffer.wrap(data);
		String queryString = RabbitMQUtils.readString(buffer);
		
		if (queryString.contains("INSERT DATA")) {
			executor.submit(() -> {
				lock.enterCriticalSection(Lock.WRITE);
				try {
					Txn.executeWrite(conn, () -> conn.update(queryString));
				} finally {
					lock.leaveCriticalSection();
				}
				try {
					this.sendResultToEvalStorage(taskId, RabbitMQUtils.writeString(""));
				} catch (IOException e) {
					LOGGER.error("Got an exception while sending results.", e);
				}
			});
			LOGGER.info("Task " + taskId + " (INSERT) submitted for execution.");
		} else {
			int currSelectCount = totalTGSelects.incrementAndGet();
			executor.submit(() -> {
				lock.enterCriticalSection(Lock.READ);
				try {
					Txn.executeRead(conn, () -> {
						ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
						try (QueryExecution qExec = conn.query(queryString)) {
							ResultSet results = qExec.execSelect();
			            	ResultSetFormatter.outputAsJSON(outputStream, results);
			            	try {
								sendResultToEvalStorage(taskId, outputStream.toByteArray());
//								LOGGER.info("Results of task " + taskId + " sent to the evaluation storage.");
							} catch (IOException e) {
								LOGGER.error("Exception while sending results of task " + taskId + " to the evaluation storage.", e);
							}
			            } catch (Exception e) {
							LOGGER.error("Problem while executing task " + taskId, e);
							try {
								outputStream.write("{\"head\":{\"vars\":[\"xxx\"]},\"results\":{\"bindings\":[{\"xxx\":{\"type\":\"literal\",\"value\":\"XXX\"}}]}}".getBytes());
								sendResultToEvalStorage(taskId, outputStream.toByteArray());
//								LOGGER.info("Results of task " + taskId + " sent to the evaluation storage.");
							} catch (IOException e1) {
								LOGGER.error("Exception while writing/sending empty results of task " + taskId, e);
							}
						}
						float f = (float) totalTGSelectsExecuted.incrementAndGet() / totalTGSelects.get() * 100;
						LOGGER.info(new DecimalFormat("#.##").format(f) + "% - SELECT " + currSelectCount + " of " + totalTGSelects.get() + " executed successfully.");
			        }); 		
				} finally {
					lock.leaveCriticalSection();
				}
			});
		}
//		LOGGER.info("Task " + taskId + " (SELECT) submitted for execution.");
	}

	@Override
	public void receiveCommand(byte command, byte[] data) {
		if (Constants.BULK_LOAD_DATA_GEN_FINISHED == command) {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			int numberOfMessages = buffer.getInt();
			boolean lastBulkLoad = buffer.get() != 0;

			LOGGER.info("Bulk loading phase (" + loadingNumber + ") begins");

			// if all data have been received before BULK_LOAD_DATA_GEN_FINISHED command received
			// release before acquire, so it can immediately proceed to bulk loading
			if(totalReceived.get() == totalSent.addAndGet(numberOfMessages)) {
				allDataReceivedMutex.release();
			}

			LOGGER.info("Wait for receiving all data for bulk load " + loadingNumber + ".");
			try {
				allDataReceivedMutex.acquire();
			} catch (InterruptedException e) {
				LOGGER.error("Exception while waitting for all data for bulk load " + loadingNumber + " to be recieved.", e);
			}
			LOGGER.info("All data for bulk load " + loadingNumber + " received. Proceed to the loading...");

			loadVersion("http://graph.version." + loadingNumber);

			LOGGER.info("Bulk loading phase (" + loadingNumber + ") is over.");
			
			LOGGER.info("Deleting all data files.");
			File theDir = new File(datasetFolderName);
			if(theDir.exists()) {
				for (File f : theDir.listFiles()) {
					f.delete();
				}
			}
			
			loadingNumber++;
			dataLoadingFinished = lastBulkLoad;
			LOGGER.info("dataLoadingFinished: " + dataLoadingFinished);
			
			// after all bulk load phases over start the Apache Jena Fuseki Server
			if(dataLoadingFinished && !fusekiServerStarted.get()) {
				fusekiServerStarted.set(startJenaFuseki());
				// for the versioning benchmark move default graph triples 
				// that previously loaded from tdbloader2 to http://graph.version.0
				if(loadingNumber > 2) {
					executor.submit(() -> {
						lock.enterCriticalSection(Lock.WRITE);
						try {
							Txn.executeWrite(conn, () -> conn.update("MOVE DEFAULT TO <http://graph.version.0>"));
						} finally {
							lock.leaveCriticalSection();
							// release after move have completed
							fusekiServerStartedMutex.release();
						}
					});
				}				
			}
			
			try {
				sendToCmdQueue(Constants.BULK_LOADING_DATA_FINISHED);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		super.receiveCommand(command, data);
	}
	
	/*
	 * Load all files contained in data path using the tdbloader.
	 * tdbloader cannot be run in parallel with fuseki server (due to transactions issues), so the server
	 * have to be started after the bulk loading phase.
	 */
	private void loadVersion(String graphURI) {
		LOGGER.info("Loading data on " + graphURI + "...");
		try {
			String scriptFilePath = System.getProperty("user.dir") + File.separator + "scripts" + File.separator + "load.sh";
			String[] command = {"/bin/bash", scriptFilePath, datasetFolderName, graphURI};
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line;
			while ((line = in.readLine()) != null) {
				LOGGER.info(line);		
			}
			p.waitFor();
			LOGGER.info(graphURI + " loaded successfully.");
			in.close();
		} catch (IOException e) {
            LOGGER.error("Exception while executing script for loading data.", e);
		} catch (InterruptedException e) {
            LOGGER.error("Exception while executing script for loading data.", e);
		}
	}
	

	private boolean startJenaFuseki() {
		try {
			String scriptFilePath = System.getProperty("user.dir") + File.separator + "scripts" + File.separator + "fuseki-server_start.sh";
			String[] command = {"/bin/bash", scriptFilePath};
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line;
			while ((line = in.readLine()) != null) {
				LOGGER.info(line);		
			}
			p.waitFor();
			in.close();
		} catch (IOException e) {
            LOGGER.error("Exception while executing script for starting Fuseki Server.", e);
		} catch (InterruptedException e) {
            LOGGER.error("Exception while executing script for starting Fuseki Server.", e);
		}
		return true;
	}
	
	private void shutdownAndAwaitTermination() {
		// Disable new tasks from being submitted
		LOGGER.info("Shutting down executor service.");
		executor.shutdown();
		try {
			// Wait for existing tasks to terminate
			LOGGER.info("Waiting for submitted tasks to executed");
			if (!executor.awaitTermination(2, TimeUnit.HOURS)) {
				// After timeout cancel currently executing tasks
				LOGGER.info("Timeout of 2 hours reached. Shutdown now.");
				executor.shutdownNow();
				// Wait a while for tasks to respond to being cancelled
				if (!executor.awaitTermination(60, TimeUnit.SECONDS))
					LOGGER.error("Executor service did not terminate");
			} else {
				LOGGER.info("All tasks executed successfully before timeout reached.");
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			LOGGER.info("Current thread interrupted. Shutdown now.");
			executor.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		} finally {
			LOGGER.info("Executor service shut down successfully.");
			allTaskExecutedOrTimeoutMutex.release();
		}
	}

	public void close() throws IOException {
		LOGGER.info("Stopping Apache Jena Fuseki.");
		shutdownAndAwaitTermination();
		try {
			allTaskExecutedOrTimeoutMutex.acquire();
		} catch (InterruptedException e) {
			LOGGER.error("An error occured while waiting for the executor service to terminate");
		}
		conn.close();		
		super.close();
		LOGGER.info("Apache Jena Fuseki has stopped.");
	}

}
