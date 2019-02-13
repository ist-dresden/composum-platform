# Creates a docker image on top of the releases sling launchpad
# Relies on a maven build to copy the sling starter into target/lib/
#
# We provide two watched directories for file installation - fileinstall-docker, which is meant to be filled
# in derived docker images, and fileinstall-user, which could be mounted by the user

FROM openjdk:10-jdk
ARG STARTERJAR

RUN set -eux; \
    groupadd -r sling --gid=999; \
    useradd -r -g sling --uid=999 --home-dir=/opt/sling/sling --shell=/bin/bash sling; \
    mkdir -p /opt/sling/sling /opt/sling/fileinstall-docker /opt/sling/fileinstall-user; \
    chown -R sling:sling /opt/sling; \
    ln -s /opt/sling/sling/logs /var/log/sling;

# JVM options
ENV JVM_OPTS \
    -Djava.awt.headless=true -server

ENV MEM_OPTS \
    -Xms32m -Xmx512m \
    -XX:MaxHeapFreeRatio=10 -XX:MinHeapFreeRatio=5 -XX:-ShrinkHeapInSteps

ENV DEBUG_OPTS \
    -agentlib:jdwp=transport=dt_socket,address=*:18080,server=y,suspend=n

# enables JMX : caution, these are development settings!
# The port has to be the same as the host port. For hostname we might also use environment variable HOST.
ENV JMX_OPTS \
    -Djava.rmi.server.hostname=0.0.0.0 \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port=28080 \
    -Dcom.sun.management.jmxremote.rmi.port=28080 \
    -Dcom.sun.management.jmxremote.local.only=false \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false

# additional options that don't belong here
ENV ADD_OPTS ""

# additional options for sling
ENV SLING_OPTS ""

ENV FILEINSTALL_OPTS \
    -Dsling.fileinstall.dir=fileinstall-docker,fileinstall-user

ENV COMMAND java

# the HTTP port
EXPOSE 8080
# attach for debugging
EXPOSE 18080
# JMX port for jconsole
EXPOSE 28080

VOLUME /opt/sling/sling
VOLUME /opt/sling/fileinstall-user
WORKDIR /opt/sling
USER sling:sling
CMD exec $COMMAND $JVM_OPTS $MEM_OPTS $DEBUG_OPTS $JMX_OPTS $FILEINSTALL_OPTS $ADD_OPTS -jar /opt/sling/org.apache.sling.starter.jar $SLING_OPTS

# from maven dependency:copy
COPY $STARTERJAR /opt/sling/org.apache.sling.starter.jar
