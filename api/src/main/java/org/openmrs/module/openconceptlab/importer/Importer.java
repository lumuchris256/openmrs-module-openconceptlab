/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.openconceptlab.importer;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.ObjectMapper;
import org.openmrs.api.ConceptService;
import org.openmrs.api.context.Daemon;
import org.openmrs.module.openconceptlab.CacheService;
import org.openmrs.module.openconceptlab.Import;
import org.openmrs.module.openconceptlab.ImportProgress;
import org.openmrs.module.openconceptlab.ImportService;
import org.openmrs.module.openconceptlab.ItemState;
import org.openmrs.module.openconceptlab.OpenConceptLabActivator;
import org.openmrs.module.openconceptlab.Subscription;
import org.openmrs.module.openconceptlab.Utils;
import org.openmrs.module.openconceptlab.client.OclClient;
import org.openmrs.module.openconceptlab.client.OclClient.OclResponse;
import org.openmrs.module.openconceptlab.client.OclConcept;
import org.openmrs.module.openconceptlab.client.OclMapping;
import org.openmrs.module.openconceptlab.scheduler.UpdateScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

public class Importer implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(Importer.class);

	public final static int BATCH_SIZE = 128;

	public final static int THREAD_POOL_SIZE = 16;

	private ImportService importService;

	private ConceptService conceptService;

	private OclClient oclClient;

	private Saver saver;

	private volatile Import anImport;

	private CountingInputStream in = null;

	private volatile long totalBytesToProcess;

	private File importFile;

	private interface Task {
		void run() throws Exception;
	}

    public void setImportService(ImportService importService) {
	    this.importService = importService;
    }

    public void setConceptService(ConceptService conceptService) {
	    this.conceptService = conceptService;
    }

    public void setOclClient(OclClient oclClient) {
	    this.oclClient = oclClient;
    }

    public void setSaver(Saver persister) {
	    this.saver = persister;
    }

	public void setImportFile(File importFile) {
		this.importFile = importFile;
	}

	/**
	 * This will return an appropriate input stream, depending on whether the passed file is a Zip or Json text file
	 */
	private void openInputStream() throws IOException {
		if (importFile != null) {
			if (importFile.getName().endsWith(".zip")) {
				ZipFile zipFile = new ZipFile(importFile);
				in = new CountingInputStream(Utils.extractExportInputStreamFromZip(zipFile));
			} else if (importFile.getName().endsWith(".json")) {
				in = new CountingInputStream(FileUtils.openInputStream(importFile));
			}
			else {
				throw new IllegalStateException("Import file " + importFile.getName() + " must be either a zip or json file");
			}
			String subscriptionUrl = importFile.getAbsolutePath();
			importService.updateSubscriptionUrl(anImport, subscriptionUrl);
		}
		else {
			Subscription subscription = importService.getSubscription();
			Import lastImport = importService.getLastSuccessfulSubscriptionImport();
			Date updatedSince = null;
			if (lastImport != null) {
				updatedSince = lastImport.getOclDateStarted();
			}

			OclResponse oclResponse;

			if (updatedSince == null) {
				oclResponse = oclClient.fetchOclConcepts(subscription.getUrl(), subscription.getToken());
				String releaseVersion = oclClient.getOclReleaseVersion(subscription.getUrl(), subscription.getToken());
				importService.updateReleaseVersion(anImport, releaseVersion);
			}
			else {
				if (subscription.isSubscribedToSnapshot()) {
					oclResponse = oclClient.fetchSnapshotUpdates(subscription.getUrl(), subscription.getToken(), updatedSince);
				}
				else {
					oclResponse = oclClient.fetchOclConcepts(subscription.getUrl(), subscription.getToken(), lastImport.getReleaseVersion());
					importService.updateReleaseVersion(anImport,
							oclClient.getOclReleaseVersion(subscription.getUrl(), subscription.getToken()));
				}
			}

			if (oclResponse != null) {
				importService.updateOclDateStarted(anImport, oclResponse.getUpdatedTo());
				importService.updateSubscriptionUrl(anImport, subscription.getUrl());
				in = new CountingInputStream(oclResponse.getContentStream());
				totalBytesToProcess = oclResponse.getContentLength();
			}
		}
	}

	private void closeInputStream() {
		IOUtils.closeQuietly(in);
	}

	private ObjectMapper getObjectMapper() {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.getDeserializationConfig().setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss"));
		return objectMapper;
	}

	/**
	 * Runs an import for a configured subscription.
	 * <p>
	 * It is not run directly, rather by a dedicated scheduler {@link UpdateScheduler}.
	 * if input stream is already defined from file, subscription logic is skipped by calling run(InputStream).
	 *
	 * @should start first import with response date
	 * @should start next import with updated since
	 * @should create item for each concept and mapping
	 */
	@Override
	public void run() {
		importCollection();
	}

	/**
	 * This run is used to import a single concept from the import file.
	 * The import file needs to be a valid OCL export, and can be in either json or zip format.
	 */
	public void importSingleConcept() {
		Daemon.runInDaemonThreadAndWait(new Runnable() {
			@Override
			public void run() {
				runAndHandleErrors(new Task() {
					@Override
					public void run() throws IOException {

						ImportTask importTask = new ImportTask(saver, new CacheService(conceptService), importService, anImport);
						ObjectMapper objectMapper = getObjectMapper();
						String subscriptionUrl = importFile.getAbsolutePath();
						importService.updateSubscriptionUrl(anImport, subscriptionUrl);
						String baseUrl = getBaseUrl();

						// First read the file into an OclConcept and add to the import task
						OclConcept oclConcept;
						try {
							openInputStream();
							JsonParser conceptParser = objectMapper.getJsonFactory().createJsonParser(in);
							oclConcept = conceptParser.readValueAs(OclConcept.class);
							oclConcept.setVersionUrl(prependBaseUrl(baseUrl, oclConcept.getVersionUrl()));
							oclConcept.setUrl(prependBaseUrl(baseUrl, oclConcept.getUrl()));
							importTask.setOclConcepts(Arrays.asList(oclConcept));
						}
						finally {
							closeInputStream();
						}

						// Next, read the file into a List of OclMapping and add these to the import task
						try {
							openInputStream();
							JsonParser mappingsParser = objectMapper.getJsonFactory().createJsonParser(in);
							JsonToken jsonToken = advanceToListOf("mappings", null, mappingsParser);
							if (jsonToken != JsonToken.END_OBJECT) {
								List<OclMapping> oclMappings = new ArrayList<>(BATCH_SIZE);
								while (mappingsParser.nextToken() != JsonToken.END_ARRAY) {
									OclMapping oclMapping = mappingsParser.readValueAs(OclMapping.class);
									oclMapping.setUrl(prependBaseUrl(baseUrl, oclMapping.getUrl()));
									oclMapping.setFromConceptUrl(prependBaseUrl(baseUrl, oclMapping.getFromConceptUrl()));
									oclMapping.setFromSourceUrl(prependBaseUrl(baseUrl, oclMapping.getFromSourceUrl()));
									oclMapping.setToConceptUrl(prependBaseUrl(baseUrl, oclMapping.getToConceptUrl()));
									// We do not import Q-AND-A mappings for which the imported concept is an answer
									boolean isQuestionAnswer = "Q-AND-A".equals(oclMapping.getMapType());
									if (isQuestionAnswer && oclMapping.getToConceptUrl().equals(oclConcept.getUrl())) {
										log.debug("Skipping Q-AND-A mapping for which the imported concept is a mapped answer");
									}
									else if (oclMapping.getToSourceName().equals(oclConcept.getSource()) && oclMapping.getToConceptCode().equals(oclConcept.getId())) {
										log.debug("Skipping SAME-AS Mapping for OCL ID as this is added in the ImportTask, and would result in duplicate mappings");
									}
									else {
										oclMappings.add(oclMapping);
									}
								}
								importTask.setOclMappings(oclMappings);
							}
						}
						finally {
							closeInputStream();
						}

						ThreadPoolExecutor runner = newRunner();
						runner.execute(importTask);
						runner.shutdown();
						try {
							runner.awaitTermination(5, TimeUnit.MINUTES);
						} catch (InterruptedException e) {
							throw new RuntimeException(e);
						}
					}
				});
			}
		}, OpenConceptLabActivator.getDaemonToken());
	}

	/**
	 * This run is used to import a single concept from an import file.
	 * The import file needs to be a valid OCL export, and can be in either json or zip format.
	 */
	public void importCollection() {
		Daemon.runInDaemonThreadAndWait(new Runnable() {
			@Override
			public void run() {
				runAndHandleErrors(new Task() {

					@Override
					public void run() throws IOException {
						try {
							openInputStream();
							if (in != null) {
								ObjectMapper objectMapper = getObjectMapper();
								JsonParser parser = objectMapper.getJsonFactory().createJsonParser(in);
								JsonToken token = parser.nextToken();
								if (token != JsonToken.START_OBJECT) {
									throw new IOException("JSON must start from an object");
								}

								// concepts are located first, navigate to those and import them
								token = advanceToListOf("concepts", "mappings", parser);
								if (token == JsonToken.END_OBJECT || token == null) {
									return;
								}
								ThreadPoolExecutor runner = newRunner();
								String baseUrl = getBaseUrl();
								List<OclConcept> oclConcepts = new ArrayList<>(BATCH_SIZE);
								while (parser.nextToken() != JsonToken.END_ARRAY) {
									OclConcept oclConcept = parser.readValueAs(OclConcept.class);
									oclConcept.setVersionUrl(prependBaseUrl(baseUrl, oclConcept.getVersionUrl()));
									oclConcept.setUrl(prependBaseUrl(baseUrl, oclConcept.getUrl()));
									oclConcepts.add(oclConcept);
									if (oclConcepts.size() >= BATCH_SIZE) {
										CacheService cacheService = new CacheService(conceptService);
										ImportTask importTask = new ImportTask(saver, cacheService, importService, anImport);
										importTask.setOclConcepts(new ArrayList<>(oclConcepts));
										oclConcepts.clear();
										runner.execute(importTask);
									}
								}
								if (oclConcepts.size() != 0) {
									ImportTask importTask = new ImportTask(saver, new CacheService(conceptService), importService, anImport);
									importTask.setOclConcepts(oclConcepts);
									runner.execute(importTask);
								}
								runner.shutdown();
								try {
									runner.awaitTermination(5, TimeUnit.MINUTES);
								} catch (InterruptedException e) {
									throw new RuntimeException(e);
								}

								// mappings are located after concepts, navigate to those and import them
								token = advanceToListOf("mappings", null, parser);
								if (token == JsonToken.END_OBJECT) {
									return;
								}
								runner = newRunner();
								List<OclMapping> oclMappings = new ArrayList<>(BATCH_SIZE);
								while (parser.nextToken() != JsonToken.END_ARRAY) {
									OclMapping oclMapping = parser.readValueAs(OclMapping.class);
									oclMapping.setUrl(prependBaseUrl(baseUrl, oclMapping.getUrl()));
									oclMapping.setFromConceptUrl(prependBaseUrl(baseUrl, oclMapping.getFromConceptUrl()));
									oclMapping.setFromSourceUrl(prependBaseUrl(baseUrl, oclMapping.getFromSourceUrl()));
									oclMapping.setToConceptUrl(prependBaseUrl(baseUrl, oclMapping.getToConceptUrl()));
									oclMappings.add(oclMapping);
									if (oclMappings.size() >= BATCH_SIZE) {
										CacheService cacheService = new CacheService(conceptService);
										ImportTask importTask = new ImportTask(saver, cacheService, importService, anImport);
										importTask.setOclMappings(new ArrayList<>(oclMappings));
										oclMappings.clear();
										runner.execute(importTask);
									}
								}
								if (oclMappings.size() != 0) {
									ImportTask importTask = new ImportTask(saver, new CacheService(conceptService), importService, anImport);
									importTask.setOclMappings(oclMappings);
									runner.execute(importTask);
								}
								runner.shutdown();
								try {
									runner.awaitTermination(5, TimeUnit.MINUTES);
								} catch (InterruptedException e) {
									throw new RuntimeException(e);
								}
							}
						}
						finally {
							closeInputStream();
						}
					}
				});
			}
		}, OpenConceptLabActivator.getDaemonToken());
	}

	private void runAndHandleErrors(Task task) {
		Import newUpdate = new Import();
		importService.startImport(newUpdate);
		anImport = newUpdate;
		totalBytesToProcess = -1; //unknown
		try {
			task.run();
			Set<ItemState> errorSet = new HashSet<>(Collections.singletonList(ItemState.ERROR));
			Integer errors = importService.getImportItemsCount(anImport, errorSet);
			if (errors > 0) {
				importService.failImport(anImport);
			}
		}
		catch (Throwable e) {
			importService.failImport(anImport, getErrorMessage(e));
			throw new ImportException(e);
		}
		finally {
			IOUtils.closeQuietly(in);
			if (importFile != null) {
				try {
					File destFile = new File(Utils.getImportDirectory(), importFile.getName());
					if (!destFile.getAbsolutePath().equals(importFile.getAbsolutePath())) {
						FileUtils.moveFile(importFile, destFile);
					}
				}
				catch(Exception e) {
					log.error("Unable to move " + importFile + " to " + Utils.getImportDirectory());
				}
			}
			importFile = null;
			try {
				if (anImport != null && anImport.getImportId() != null) {
					importService.stopImport(anImport);
				}
			}
			catch (Exception e) {
				log.error("Failed to stop anImport", e);
			}
			in = null;
			totalBytesToProcess = 0;
			anImport = null;
		}
	}

	public static String getErrorMessage(Throwable e) {
		String message = e.getMessage();
		Throwable rootCause = ExceptionUtils.getRootCause(e);
		if (rootCause == null) {
			rootCause = e;
		}

		String[] stackFrames = ExceptionUtils.getStackFrames(rootCause);
		int endIndex = Math.min(stackFrames.length, 5);
		message += "\n caused by: " + StringUtils.join(stackFrames, "\n", 0, endIndex);

		if (message.length() > 1024) {
			return message.substring(0, 1024);
		} else {
			return message;
		}
	}

	public long getBytesDownloaded() {
		return oclClient.getBytesDownloaded();
	}

	public long getTotalBytesToDownload() {
		return oclClient.getTotalBytesToDownload();
	}

	public boolean isDownloaded() {
		return oclClient.isDownloaded();
	}

	public long getBytesProcessed() {
		if (in != null) {
			return in.getByteCount();
		} else {
			return 0;
		}
	}

	public long getTotalBytesToProcess() {
		return totalBytesToProcess;
	}

	public boolean isProcessed() {
		return totalBytesToProcess == getBytesProcessed();
	}

	public boolean isRunning() {
		Import lastUpdate = importService.getLastImport();
		if (lastUpdate == null) {
			return false;
		}

		if (anImport == null && !lastUpdate.isStopped()) {
			lastUpdate.setErrorMessage("Process terminated before completion");
			importService.stopImport(lastUpdate);
		}

		return anImport != null;
	}

	private String getBaseUrl() {
		String baseUrl = "";
		if (importService.getSubscription() != null) {
			baseUrl = importService.getSubscription().getUrl();
			if (baseUrl != null) {
				try {
					URI uri = new URI(baseUrl);
					baseUrl = uri.getScheme() + "://" + uri.getHost();
					if (uri.getPort() != -1) {
						baseUrl += ":" + uri.getPort();
					}
				}
				catch (Exception e) {
					throw new IllegalStateException(baseUrl + " is not valid", e);
				}
			}
		}
		return baseUrl;
	}

	private ThreadPoolExecutor newRunner() {
		return new ThreadPoolExecutor(0, THREAD_POOL_SIZE, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(
		        THREAD_POOL_SIZE / 2), new RejectedExecutionHandler() {

					@Override
					public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
						try {
	                        executor.getQueue().put(r);
                        }
                        catch (InterruptedException e) {
                        	throw new RejectedExecutionException("Work discarded", e);
                        }
					}
				});
	}

	private String prependBaseUrl(String baseUrl, String url) {
		if (baseUrl == null) {
			return url;
		}
		if (url == null) {
			return null;
		}

		if (!url.startsWith("/")) {
			url = "/" + url;
		}
		return baseUrl + url;
	}

	private JsonToken advanceToListOf(String field, String stopAtField, JsonParser parser) throws IOException {
		JsonToken token = parser.getCurrentToken();
		if (token == null) {
			token = parser.nextToken();
		}

		do {
			if (token == JsonToken.START_OBJECT) {
				String text = parser.getText();
				while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
					if (token == null) {
						throw new IOException("Missing end of object: " + text);
					}
				}
			} else if (parser.getText().equals(field)) {
				token = parser.nextToken();
				if (token != JsonToken.START_ARRAY) {
					throw new ImportException(field + " must be a list");
				}
				return token;
			} else if (token == JsonToken.START_ARRAY) {
				String text = parser.getText();
				while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
					if (token == null) {
						throw new IOException("Missing end of array: " + text);
					}
				}
			} else if (stopAtField != null && parser.getText().equals(stopAtField)) {
				return token;
			}
		} while ((token = parser.nextToken()) != null);

		return null;
	}

	public ImportProgress getImportProgress(String uuid){
		ImportProgress updateProgress = new ImportProgress();

		Import oclImport;
		if (StringUtils.isNotBlank(uuid)) {
			oclImport = importService.getImport(uuid);
		} else {
			oclImport = importService.getLastImport();
		}

		if (oclImport.getLocalDateStopped() == null) {
			long time = (new Date().getTime() - oclImport.getLocalDateStarted().getTime()) / 1000;
			updateProgress.setTime(time);

			if (!isDownloaded()) {
				double totalBytesToDownload = getTotalBytesToDownload();
				double progress;
				if (getBytesDownloaded() == 0) {
					//simulate download progress until first bytes are downloaded
					progress = (double) time / (time + 5) * 10.0;
				} else if (getTotalBytesToDownload() == -1) {
					//simulate download progress since total bytes to download are unknown
					progress = 10.0 + ((double) time / (time + 100) * 20.0);
				} else {
					progress = 10.0 + ((double) getBytesDownloaded() / totalBytesToDownload * 20.0);
				}
				updateProgress.setProgress((int) progress);
			} else if (!isProcessed()) {
				double progress = 30;
				if (getTotalBytesToProcess() == -1) {
					progress += ((double) time / (time + 100) * 70.0);
				} else {
					progress += ((double) getBytesProcessed() / getTotalBytesToProcess() * 70.0);
				}

				updateProgress.setProgress((int) progress);
			} else {
				updateProgress.setProgress(100);
			}
		} else {
			updateProgress.setProgress(100);
			updateProgress.setTime((oclImport.getLocalDateStopped().getTime() - oclImport.getLocalDateStarted().getTime()) / 1000);
		}

		return updateProgress;
	}

}
