#!/bin/bash

function logdate {
    date -u '+%d.%m.%Y %H:%M:%S'
}

logfile=/opt/sling/sling/logs/error.log

# waits until there are no deployment activities on the server for 10 seconds
function waituntilquiet {
    `dirname $0`/WaitForServerUp.jsh $logfile
}


sleep 20 # give server some undisturbed startup time

echo `logdate` start stepwise deploying stuff

cd /opt/sling/fileinstall-docker
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p ../fileinstall/$dir
        echo `logdate` checking `pwd`/$dir
        for fil in $dir/*; do
            if test \! -e "../fileinstall/$fil"; then
                echo `logdate` deploying $fil
                ln -s `pwd`/$fil ../fileinstall/$dir/
            fi
        done
        waituntilquiet
    done
fi

cd /opt/sling/fileinstall-user
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p ../fileinstall/$dir
        echo `logdate` checking `pwd`/$dir
        for fil in $dir/*; do
            if test \! -e "../fileinstall/$fil"; then
                echo `logdate` deploying $fil
                ln -s `pwd`/$fil ../fileinstall/$dir/
            fi
        done
        waituntilquiet
    done
fi

echo `logdate` finished stepwise deploying stuff at `date`
