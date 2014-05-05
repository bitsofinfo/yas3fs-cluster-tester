package org.bitsofinfo.yas3fs;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.amazonaws.services.sns.model.ListTopicsResult;
import com.amazonaws.services.sns.model.PublishResult;
import com.amazonaws.services.sns.model.Topic;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;


/**
 * Intended to run on a node that has a local yas3fs mount point
 * Will load its run configuration properties from the System 
 * property 'configFilePath' or will prompt for input.
 * 
 * @see https://github.com/bitsofinfo/yas3fs-cluster-tester
 * @see https://github.com/danilop/yas3fs
 * 
 * @author bitsofinfo.g[at]g mail com
 *
 */
public class Yas3fsMountTest {

	private static String getUserInput(String query) throws Exception {
		String input = null;
		while(input == null || input.isEmpty()) {
			System.out.print(query);
			BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));                      
			input = stdin.readLine();
		}
		
		return input;
		
	}
	
	
	// list of Long candiate file sizes in bytes for random generation
	private static List<Long> fileSizes = null;
	
	// unique identifier for this node
	private static String mySourceIdentifier = null;

	// for json event marshalling
	private static Gson gson = new GsonBuilder().setPrettyPrinting().create();
	
	// flagged to true when thread #1 is done writing all 
	// local generated files to the S3 yas3fs mount
	private static boolean copyTo_S3isDone = false;
	
	// flagged to true when thread #2 is done copying
	// all peer-node generated yas3fs files from the yas3fs local
	// mount to the verify dir
	private static boolean copyFrom_S3isDone = false;
	
	// list of table of contents files that need to be verified after everything is done
	private static List<String> tocsFiles2VerifyLocally = new ArrayList<String>();
	
	/**
	 * Main run method
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		
		AmazonS3Client s3client = null;
		AmazonSNSClient snsClient = null;
		AmazonSQSClient sqsClient = null;
		String sqsQueueUrl = null;
		String snsSubscriptionARN = null;
		String snsTopicARN = null;
		
		try {
			
			mySourceIdentifier = determineHostName() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0,4);
			
			String localTmpDir = null;
			Integer maxDirsPerDirOverall = null;
			Integer maxFilesPerDirOverall = null;
			Integer maxDirDepth = null;
			Integer maxFiles = null;
			String rawSizes = null;
			Integer maxCopyFromS3Attempts = null;
			Long copyFromS3RetrySleepMS = null;
			String verifyDir = null;
			String s3MountPath = null;
			
			String s3MountTestSNSTopicName = null;
			String awsAccessKey = null;
			String awsSecretKey = null;
			String userAccountId = null;
			
			// props file specified?
			Properties props = new Properties();
			String confPath = System.getProperty("configFilePath");
			InputStream input = null;
			if (confPath != null) {
				try {
					System.out.println("Attempting to load props from: " + confPath);
					input = new FileInputStream(confPath);
					props.load(input);
				} catch(Exception e) {
					e.printStackTrace();
					throw e;
				}
				
				
				localTmpDir = props.getProperty("local.generate.dir");
				verifyDir = props.getProperty("local.verify.dir");
				s3MountPath = props.getProperty("local.s3mount.dir");
				
				maxDirsPerDirOverall = Integer.valueOf(props.getProperty("max.dirs.per.dir.overall"));
				maxFilesPerDirOverall = Integer.valueOf(props.getProperty("max.files.per.dir.overall"));
				maxDirDepth = Integer.valueOf(props.getProperty("max.dir.depth"));
				maxFiles = Integer.valueOf(props.getProperty("max.files.overall"));
				
				rawSizes = props.getProperty("candidate.file.sizes.comma.delimited");
				maxCopyFromS3Attempts = Integer.valueOf(props.getProperty("max.attempts.to.copy.from.s3"));
				copyFromS3RetrySleepMS = Long.valueOf(props.getProperty("max.attempts.to.copy.from.s3.sleepTimeMS"));
				
				s3MountTestSNSTopicName = props.getProperty("aws.sns.topic.name");
				awsAccessKey = props.getProperty("aws.sns.access.key");
				awsSecretKey = props.getProperty("aws.sns.secret.name");
				userAccountId = props.getProperty("aws.user.account.id");
				
				
			// no props, just collect input
			} else {
				localTmpDir = getUserInput("Full path to local dir to write test file tree into (NO TRAILING SLASH): ");
				maxDirsPerDirOverall = Integer.valueOf(getUserInput("Max dirs per 'dir' overall (number): "));
				maxFilesPerDirOverall = Integer.valueOf(getUserInput("Max files per 'dir' overall (number): "));
				maxDirDepth = Integer.valueOf(getUserInput("Max directory depth (number): "));
				maxFiles = Integer.valueOf(getUserInput("Max files to generate (number): "));
				
				rawSizes = getUserInput("Candidate file sizes, in bytes, numeric (comma delimited): ");
				maxCopyFromS3Attempts = Integer.valueOf(getUserInput("Max attempts to copy file from S3mount when event received from peer-node: "));
				copyFromS3RetrySleepMS = Long.valueOf(getUserInput("Sleep time ms between retrying copy from S3 attempts: "));
				verifyDir = getUserInput("Full path to local *verify* dir " +
						"(where we will copy FROM the s3mount for verification) (NO TRAILING SLASH): ");
				
				s3MountPath = getUserInput("Local path to the S3 " +
						"local directory mount i.e. /mnt/yas3fsMount (NO TRAILING SLASH): ");
				
				
				s3MountTestSNSTopicName = getUserInput("AWS SNS Topic name for this program to communicate to other peers (will create if not exists): ");
				awsAccessKey = getUserInput("AWS ACCESS KEY for accessing SNS topic: ");
				awsSecretKey = getUserInput("AWS SECRET KEY for accessing SNS topic: ");
				userAccountId = getUserInput("AWS User account ID (numeric) for above credentials: ");
				
			}
			
			
			fileSizes = new ArrayList<Long>();
			String[] rawSizesArr = rawSizes.split(",");
			for (String fsize : rawSizesArr) {
				fileSizes.add(Long.valueOf(fsize));
			}
			
			
			System.out.println("Generating test files under: " + localTmpDir + ".......");
			
			List<FileInfo> infos = new ArrayList<FileInfo>();
			generateFiles(infos, localTmpDir, maxDirsPerDirOverall, maxFilesPerDirOverall, new int[]{0}, maxDirDepth, new int[]{maxFiles});
			
			File localTOC = generateTOCFiles(infos, localTmpDir, true);
			File S3TOC = generateTOCFiles(infos, localTmpDir, false);
			
			System.out.println("Test files generated OK under: " + localTmpDir +
					", file LOCAL TOC dumped to file @ " + localTOC.getAbsolutePath());
			
			System.out.println("Test files generated OK under: " + localTmpDir +
					", file S3 TOC dumped to file @ " + S3TOC.getAbsolutePath());
			

			System.out.println("---------------------------------------------------------------------------");
			System.out.println("      Local test files generated OK, MOUNT the remote S3 bucket now!     ");
			System.out.println("---------------------------------------------------------------------------");
			
			getUserInput("Type anything if S3 bucket is mounted and ready to proceeed @ " + s3MountPath);


			/*
			 * Spawn 2 threads
			 *    a) one that starts copying the files from the local test tree into S3
			 *    
			 *    b) the second thread that listens to the SNS -> SQS queue
			 *    	- receives the messages, each message is a path to a file pushed by an 
			 *    	  Yas3fsMountTest instance in format sourceHost:s3RelativePath
			 *      - for each list, run in a slow loop, continually checking for the existence of these files
			 */
			s3client = new AmazonS3Client(new BasicAWSCredentials(awsAccessKey, awsSecretKey));
			snsClient = new AmazonSNSClient(new BasicAWSCredentials(awsAccessKey, awsSecretKey));;
			sqsClient = new AmazonSQSClient(new BasicAWSCredentials(awsAccessKey, awsSecretKey));;
			
			// create SNS topic
			
			ListTopicsResult listResult = snsClient.listTopics();
			List<Topic> topics = listResult.getTopics();
			
			while(topics != null) {
				
				for (Topic topic : topics) {
					if (topic.getTopicArn().indexOf(s3MountTestSNSTopicName) != -1) {
						snsTopicARN = topic.getTopicArn();
						System.out.println("Found existing SNS topic by name: "+s3MountTestSNSTopicName + " @ " + snsTopicARN);
						break;
					}
				}

				String nextToken = listResult.getNextToken();
				
				if (nextToken != null && snsTopicARN == null) {
					listResult = snsClient.listTopics(nextToken);
					topics = listResult.getTopics();
					
				} else {
					break;
				}
			}
			
			
			if (snsTopicARN == null) {
				CreateTopicResult createTopicResult = snsClient.createTopic(s3MountTestSNSTopicName);
				snsTopicARN = createTopicResult.getTopicArn();
				snsClient.addPermission(snsTopicARN, "Permit_SNSAdd", Arrays.asList(new String[]{userAccountId}), Arrays.asList(new String[]{"Publish"}));
				System.out.println("Created new SNS topic by name: "+s3MountTestSNSTopicName + " @ " + snsTopicARN);
			}
			
			// create SQS queue to get SNS:yas3fsMountTest notifications
			String sqsQueueName = "yas3fsMountTest_" + mySourceIdentifier;
			CreateQueueResult createQueueResult = sqsClient.createQueue(sqsQueueName);
			sqsQueueUrl = createQueueResult.getQueueUrl();
			String sqsQueueARN = sqsClient.getQueueAttributes(sqsQueueUrl, Arrays.asList(new String[]{"QueueArn"})).getAttributes().get("QueueArn");
			sqsClient.addPermission(sqsQueueUrl, "Permit_SQSAdd",  Arrays.asList(new String[]{userAccountId}), Arrays.asList(new String[]{"SendMessage"}));
			System.out.println("Created SQS queue: " + sqsQueueARN + " @ " + sqsQueueUrl);
			
			// subscribe our SQS queue to the SNS:s3MountTest topic
			snsSubscriptionARN = snsClient.subscribe(snsTopicARN,"sqs",sqsQueueARN).getSubscriptionArn();
			System.out.println("Subscribed for messages from SNS:" + snsTopicARN + " ----> SQS:"+sqsQueueARN);

			System.out.println("-------------------------------------------");
			System.out.println("    ALL SNS/SQS resources hooked up OK  ");
			System.out.println("-------------------------------------------");
			
			String ready = getUserInput("Ready to run? y/n (remember to answer on all servers/shells at near-same time): ");
			
			if (!ready.equalsIgnoreCase("y")) {
				return;
			}
			
			
			// copy the TOC files to S3
			execCopy(localTOC.getAbsolutePath(), s3MountPath + "/");
			execCopy(S3TOC.getAbsolutePath(), s3MountPath + "/");

			
			/*
			 * Spawn 2 threads
			 *    a) one that starts copying the files from the local test tree into S3
			 *    
			 *    b) the second thread that listens to the SNS -> SQS queue
			 *    	- receives the messages, each message is a path to a file pushed by an Yas3fsMountTest instance in format sourceHost:s3RelativePath
			 *      - for each list, run in a slow loop, continually checking for the existence of these files
			 */
			
			// publish the S3 TOC to the topic 
			snsClient.publish(snsTopicARN, gson.toJson(new Payload(mySourceIdentifier,"toc",getFileContents(S3TOC.getAbsolutePath()))));
			
			// THREAD 1 (copies generated file structure -> S3 and publishes each copy info to SNS topic)
			Thread copyToS3Thread = new Thread(new Local2S3FileCopier(snsClient, snsTopicARN, localTOC.getAbsolutePath(),localTmpDir,s3MountPath));
			copyToS3Thread.start();
			
			// THREAD 2 (responds to messages from SQS queue to copy files from the mount(s3) to local)
			Thread copyFromS3Thread = new Thread(new S32LocalFileCopier(sqsClient, sqsQueueUrl, s3MountPath, verifyDir, maxCopyFromS3Attempts, copyFromS3RetrySleepMS));
			copyFromS3Thread.start();

			// wait for Thread1 and Thread2 to finish
			while(!copyFrom_S3isDone || !copyTo_S3isDone) {
				Thread.currentThread().sleep(2000);
			}
			
			System.out.println("-------------------------------------------");
			System.out.println("    ALL THREADS DONE, Proceeding to verify local file existance for files generated by other hosts/processes  ");
			System.out.println("-------------------------------------------");
			
			ready = getUserInput("Ready? y/n (remember to answer on all servers/shells): ");
			
			if (!ready.equalsIgnoreCase("y")) {
				return;
			}
			
			// now verify all TOC files our Thread #2 (S32LocalFileCopier) received/copied
			// that we need to verify exist locally now
			for (String fileToVerifyLocally : tocsFiles2VerifyLocally) {
				File fileToCheckLocally = new File(verifyDir +"/" +fileToVerifyLocally);
				if (!fileToCheckLocally.exists()) {
					System.out.println("ERROR: File expected to exist from other process, does NOT exist: " + fileToCheckLocally.getAbsolutePath());
				} else {
					System.out.println("OK: File expected to exist from other process, does exist OK: " + fileToCheckLocally.getAbsolutePath());
				}
			}
			
			
		} catch(Exception e) {
			e.printStackTrace();
			
		} finally {
			
			if (snsClient != null) {
				try {
					snsClient.unsubscribe(snsSubscriptionARN);
				} catch(Exception e) {
					System.out.println("Cleanup error: " + e.getMessage());
				}
			}
			
			if (sqsClient != null) {
				try {
					sqsClient.deleteQueue(sqsQueueUrl);
				} catch(Exception e) {
					System.out.println("Cleanup error: " + e.getMessage());
				}
			}
			
			/** DO NOT delete because other nodes might still be using it! 
			if (snsClient != null) {
				try {
					snsClient.deleteTopic(snsTopicARN);
				} catch(Exception e) {
					System.out.println("Cleanup error: " + e.getMessage());
				}
			}
			**/
			
		}
		
		
		
	}
	
	public static String getFileContents(String pathToFile) throws Exception{
		BufferedReader bufferedReader = new BufferedReader(new FileReader(new File(pathToFile)));
		 
		  StringBuffer stringBuffer = new StringBuffer();
		  String line = null;
		 
		  while((line =bufferedReader.readLine())!=null){
		 
		   stringBuffer.append(line).append("\n");
		  }
		  
		  return stringBuffer.toString();
	}
	
	/**
	 * Listens to the SNS(sqs) topic, when an file copy event
	 * is received (that was generated by another node) this 
	 * will attempt to copy it via the yas3fs local mount to 
	 * a different local directory, hence verifying it 
	 * is actually in S3. Will obey retry behavior as configured
	 * 
	 */
	public static class S32LocalFileCopier implements Runnable{
		
		private AmazonSQSClient sqsClient = null;
		private String s3MountPath = null;
		private String sqsQueueUrl = null;
		private int maxCopyAttempts = 5;
		private long retrySleepTimeMS = 10000;
		private String verifyDir = null;
		
		public S32LocalFileCopier(AmazonSQSClient sqsClient, String sqsQueueUrl, String s3MountPath, String verifyDir, int maxCopyAttempts, long retrySleepTimeMS) {
			this.sqsQueueUrl = sqsQueueUrl;
			this.s3MountPath = s3MountPath;
			this.sqsClient = sqsClient;
			this.maxCopyAttempts = maxCopyAttempts;
			this.retrySleepTimeMS = retrySleepTimeMS;
			this.verifyDir = verifyDir;
		}
		
		@Override 
		public void run() {
			
			int messageGetsSinceCopyTo = 0;
			int maxMessageGetsSinceCopyTo = 20;
			
			while(true) {
				
				try {
					ReceiveMessageResult msgResult = sqsClient.receiveMessage(sqsQueueUrl);
					List<Message> messages = msgResult.getMessages();
					int lastMessagesReceived = messages.size();
					
					if (lastMessagesReceived == 0) {

						System.out.println("messageGetsSinceCopyTo = " + messageGetsSinceCopyTo);
						
						if (copyTo_S3isDone && messageGetsSinceCopyTo >= maxMessageGetsSinceCopyTo) {
							copyFrom_S3isDone = true;
							return;
							
						} else if (copyTo_S3isDone) {
							messageGetsSinceCopyTo++;
						}
					}
					
					for (Message msg : messages) {
						
						Map<String,String> body = gson.fromJson(msg.getBody(), new TypeToken<Map<String, String>>(){}.getType()); 
						Payload payload = gson.fromJson(body.get("Message"), Payload.class);
						System.out.println("Payload received: source: " + payload.sourceHost + ", type:" + payload.type + " data:" + payload.data);
					
						// if NOT equal to THIS MACHINE...
						if (!payload.sourceHost.equalsIgnoreCase(mySourceIdentifier)) {
							
							// table of contents
							if (payload.type.equalsIgnoreCase("toc")) {
								System.out.println("Received TOC " + payload.data);
								
								String[] paths = payload.data.split("\\r?\\n");
								for (String path : paths) {
									tocsFiles2VerifyLocally.add(path);
								}
								
							// file copied info
							} else {
								String from = s3MountPath + payload.data;
								String to = verifyDir + payload.data;
								System.out.println("Copying " +from + " -> " + to);
								
								int copyAttempts = 0;
								boolean copied = false;
								while(copyAttempts < maxCopyAttempts) {
									try {
										execCopy(from, to);
										copied = true;
										break;
									} catch(Exception e) {
										copyAttempts++;
										System.out.println("Received event from another node["+payload.sourceHost+"], S3mount copy to LOCAL: " +
												"failed to copy " + from + " -> " + to + 
												" attempt #: " + copyAttempts + " of " + maxCopyAttempts + ", sleeping: " + retrySleepTimeMS+ "MS: " + e.getMessage());
										Thread.currentThread().sleep(retrySleepTimeMS);
									}
								}
									
								if (!copied) {
									System.out.println("Received event from another node["+payload.sourceHost+"], ERROR: GAVE UP! S3mount to LOCAL: " +
										"failed to copy " + from + " -> " + to);
								}
							}
						}
						
						// delete the message we just analyzed
						sqsClient.deleteMessage(sqsQueueUrl, msg.getReceiptHandle());
					}
					
					Thread.currentThread().sleep(2000);
					
				} catch(Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	
	/**
	 * Copies all generated files one by one to the local
	 * yas3fs mount point. After each copy to yas3fs, an SNS
	 * event is broadcast
	 *
	 */
	public static class Local2S3FileCopier implements Runnable{
		
		private String localFileTOC = null;
		private String s3MountPath = null;
		private String localFilePathRoot = null;
		private AmazonSNSClient snsClient = null;
		private String snsTopicARN = null;
		
		public Local2S3FileCopier(AmazonSNSClient snsClient, String snsTopicARN, String localFileTOC, String localFilePathRoot, String s3MountPath) {
			this.localFileTOC = localFileTOC;
			this.s3MountPath = s3MountPath;
			this.snsClient = snsClient;
			this.snsTopicARN = snsTopicARN;
			this.localFilePathRoot = localFilePathRoot;
		}
		
		@Override 
		public void run() {
			try {
				BufferedReader tocReader = new BufferedReader(new FileReader(new File(localFileTOC)));
				
				// http://commons.apache.org/proper/commons-exec/download_exec.cgi
				String localFilePath = null;
				while((localFilePath = tocReader.readLine()) != null) {
					
					String relativeFilePath = localFilePath.replace(localFilePathRoot, "");
					String s3Path = s3MountPath + relativeFilePath;
					
					
					try {
						execCopy(localFilePath, s3Path);
					} catch(Exception e) {
						System.out.println("ERROR: LOCAL -> S3mount: " +
								"failed to copy " + localFilePath + " -> " + s3Path + " exception:" + e.getMessage());
					}
					
					
					// publish it
					PublishResult pubResult = snsClient.publish(snsTopicARN, gson.toJson(new Payload(mySourceIdentifier,"file",relativeFilePath)));
				}
				
				// mark we are done
				Yas3fsMountTest.copyTo_S3isDone = true;
				
			} catch(Exception e) {
				e.printStackTrace();
			}
		}

		
	}
	
	private static void execCopy(String from, String to) throws Exception {
		
		File toParentPath = new File(to.substring(0,to.lastIndexOf("/")));
		if (!toParentPath.exists()) {
			System.out.println("Parent path does not exist, creating mkdirs(): " + toParentPath);
			toParentPath.mkdirs();
		}
		
		String command = "cp " + from + " " + to;
		System.out.println("Executing: " + command);
		
		CommandLine cmdLine = CommandLine.parse(command);
		DefaultExecutor executor = new DefaultExecutor();
		int exitValue = executor.execute(cmdLine);
		if (exitValue > 0) {
			System.out.println("ERROR: exitCode: "+exitValue+" cmd=" + command);
		}
	}
	
	private static File generateTOCFiles(List<FileInfo> infos, String localTmpDir, boolean local) throws Exception {
		File file = new File(localTmpDir + "/_TOC_"+(local ? "local":"s3")+"_" + mySourceIdentifier + "-" + UUID.randomUUID().toString().replace("-", "").substring(0,4) + ".txt");
		FileWriter writer = new FileWriter(file);
		for (FileInfo info : infos) {
			if (local) {
				writer.write(info.absolutePath+ "\n");
			} else {
				// strip the local tmpdir, show relative
				writer.write(info.absolutePath.replace((localTmpDir+"/"), "") +"\n");
			}
		}
		writer.close();
		return file;
	}

	
	private static String determineHostName() throws Exception {

		InetAddress addr = InetAddress.getLocalHost();
		// Get IP Address
		byte[] ipAddr = addr.getAddress();
		// Get sourceHost
		String tmpHost = addr.getHostName();

		// we only care about the HOST portion, strip everything else
		// as some boxes report a fully qualified sourceHost such as
		// host.domainname.com

		int firstDot = tmpHost.indexOf('.');
		if (firstDot != -1) {
			tmpHost = tmpHost.substring(0,firstDot);
		}
		return tmpHost;

	}
	
	private static void generateFiles(List<FileInfo> allFiles, 
									  String rootDirectory, 
									  Integer maxDirsPerDirOverall,
	 								  Integer maxFilesPerDirOverall,
	 								  int[] currDirDepthHolder,
	 								  int maxDirDepth,
									  int[] totalFilesOverallHolder) throws Exception {
		
		
		SecureRandom rand = new SecureRandom();
		
		currDirDepthHolder[0]++;
		
		int totalPossibleFilesHere = rand.nextInt(maxFilesPerDirOverall);
		
		while(totalFilesOverallHolder[0] > 0 && totalPossibleFilesHere > 0) {
			
			File newFile = generateFile(rootDirectory, fileSizes.get(rand.nextInt(fileSizes.size())));
			allFiles.add(new FileInfo(newFile.getAbsolutePath(),newFile.length()));
			totalFilesOverallHolder[0]--;
			totalPossibleFilesHere--;
		}
		
		if (totalFilesOverallHolder[0] == 0) {
			currDirDepthHolder[0]--;
			return;
		}
		
		if (currDirDepthHolder[0] < maxDirDepth) {
			
			int totalPossibleDirsHere = rand.nextInt(maxDirsPerDirOverall)+1;
			for (int i=0; i<totalPossibleDirsHere; i++) {
				File dir = new File(rootDirectory + "/dir_" + UUID.randomUUID().toString().replace("-", "").substring(0,6));
				dir.mkdir();

				generateFiles(allFiles,dir.getAbsolutePath(),maxDirsPerDirOverall,maxFilesPerDirOverall, currDirDepthHolder, maxDirDepth, totalFilesOverallHolder);
			}
			
			// still some to do?
			if (totalFilesOverallHolder[0] > 0) {
				generateFiles(allFiles,rootDirectory,maxDirsPerDirOverall,maxFilesPerDirOverall, currDirDepthHolder, maxDirDepth, totalFilesOverallHolder);
			}
		}
		
		
		// decrement when we leave
		currDirDepthHolder[0]--;
		
	}
	
	
	
	private static File generateFile(String rootPath, long totalSize) throws Exception {
		File file = new File(rootPath + "/file_" + UUID.randomUUID().toString().replace("-", "").substring(0,12)+ ".txt");
		file.createNewFile();
		OutputStream stream = new BufferedOutputStream(new FileOutputStream(file));
		writeBytesAndClose(totalSize, stream);
		stream.close();
		return file;
	}
	
	private static void writeBytesAndClose(long totalSize, OutputStream stream) throws Exception {
		byte[] data = "x".getBytes();
		for (long i=0; i<totalSize; i++) {
			stream.write(data, 0, data.length);
		}
		stream.close();
	}
	
	public static class FileInfo {
		public String absolutePath = null;
		public long size = 0;
		public FileInfo(String absolutePath, long size) {
			super();
			this.absolutePath = absolutePath;
			this.size = size;
		}
	}
}
