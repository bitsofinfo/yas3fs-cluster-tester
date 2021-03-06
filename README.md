yas3fs-cluster-tester
=====================

Test harness to induce file io and validate [yas3fs](https://github.com/danilop/yas3fs) cluster activity across a N [yas3fs](https://github.com/danilop/yas3fs) peer-nodes.

This may be useful to anyone who wants to validate/test [yas3fs](https://github.com/danilop/yas3fs) to see how it behaves under load and with N peer's all managing files in the same S3 bucket. This has been used to test [yas3fs](https://github.com/danilop/yas3fs) 20+ node "clusters" spread across separate data-centers, with each node generating thousands of files upwards of 5-15gb total in each node's file set size (total 'cluster' file-set > ~75g -> 100gb) . Current results are pretty promising, in such large scale tests, we've seen only a handful of missing files on a handful of participating nodes. Things can likely be done to improve yas3fs's robustness to recover in these situations.

## General Concepts

1. This is a command line program
2. Each program instance collects your configuration input (via console or properties file) and generates a directory structure locally on your disk per your configuration
3. After the generated tree of files is created it will connect itself to a SNS topic (via an SQS queue) for its own peer-to-peer event communication
4. Once the SNS/SQS resources are subscribed to it spawns 2 threads
  * Thread 1: Copies each file from the generated local tree into the local yas3fs mount point (and hence to S3). After yas3fs reports that the file is copied, this thread publishes an event about this file so other peers can be notified.
  * Thread 2: Listens to SNS topic events from other peers. (ignores events it created itself). Once received it attempts to copy that file that another node stated it copied to a different local 'verify' directory. Due to the way yas3fs writes files to S3 in the background, when the event is received, the file may not actually be in S3 yet, so for this you can configure a retry policy for this thread.
5. Once both thread 1 and thread 2 are completed, on the node, and all other nodes, you can have the program validate that its local verify directory actually contains all the files all the other peer nodes stated that they wrote through yas3fs. 
6. The end result is the program prints the output of files succesfully verified vs those that don't exist when they should locally in the verify dir.

Overall you can use this for whatever purpose you wish in testing yas3fs; see how performant yas3fs is, reliable, fault tolerant, CPU/IO/Memory impact etc. on local nodes.

## How to run

* Clone this repository
* You need a Java JDK installed preferable 1.6+
* You need [Maven](http://maven.apache.org/) installed
* Change dir to the root of the project and run 'mvn package' (this will build a runnable Jar under target/)
* Edit the yas3fsTester.properties file in the root of the project
* run `java -jar -DconfigFilePath=/path/to/yas3fsTester.properties /path/to/yas3fs-cluster-tester-1.0.jar`
* NOTE: you can run it optionally without a properties file, it will prompt you for all configuration
