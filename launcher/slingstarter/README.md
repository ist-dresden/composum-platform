# Contents

This creates a docker image that starts the plain sling launchpad with JDK 11, providing debugging and JMX.

## Compose-files

For testing, there are some docker-compose files. You need to do a mvn install before calling them:

- docker-compose-local-filesystem.yml : for a quick check of the created docker image: start with a file system based CR.

