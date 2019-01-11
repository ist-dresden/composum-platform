# Adds the libraries from the copied maven dependencies into the basic sling launchpad fileinstall-docker directory
# Relies on a maven build to copy the sling starter into target/lib/
# See comments of base image!

ARG PROJECT_VERSION
FROM composum/slingstarter:${PROJECT_VERSION}

# from maven
COPY target/lib/* /opt/sling/fileinstall-docker/
