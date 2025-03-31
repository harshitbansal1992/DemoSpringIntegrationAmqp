# Steps to reproduce 

* Start the rabbit mq using docker-compose inside docker-local. Use command docker-compose up -d rabbitmq01
* Then just run spring boot application.
* Push a simple message `{"event": 1}` to  `migration.q`
* Currently the project is on spring boot 3 versionMigrationFlow3. MigrationFlow3 prints to console the received time and retry time. Time differnece between retry and received is around 36-37 seconds.
* If you update the parent bom version in pom.xml to 2.7.18, MigrationFlow2 prints the time differnece between retry and received is around 6-7 seconds.
* Seems the receiver is blocked for 30 more seconds.
* MigrationFlow3 is spring boot 3 compatible. MigrationFlow2 is spring boot 2 compatible.
* Increasing the concurrent consumer reads the retry message immediately. It proves that the receiver remains blocked.
* 

