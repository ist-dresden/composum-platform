#!/bin/bash

# set -evx

function logdate {
    date -u '+%d.%m.%Y %H:%M:%S'
}

logfile=/opt/sling/sling/logs/error.log

# waits until there are no deployment activities on the server for 10 seconds
function waituntilquiet {
    `dirname $0`/WaitForServerUp.jsh $logfile
}

# First copy stuff together into intermediatedir to be able to
# override stuff from the docker image.
intermediatedir=/opt/sling/fileinstall-joined
targetdir=/opt/sling/fileinstall

/bin/rm -fr $intermediatedir $targetdir
mkdir $intermediatedir $targetdir

cd /opt/sling/fileinstall-docker
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p $intermediatedir/$dir
        for fil in $dir/*; do
            ln -s `pwd`/$fil $intermediatedir/$dir/
        done
    done
fi

cd /opt/sling/fileinstall-user
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p $intermediatedir/$dir
        for fil in $dir/*; do
            ln -fs `pwd`/$fil $intermediatedir/$dir/
        done
    done
fi

sleep 20 # give server some undisturbed startup time

echo `logdate` start stepwise deploying stuff

# Now stepwise move the links to the install directory
cd $intermediatedir
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p $targetdir/$dir
        echo `logdate` checking `pwd`/$dir
        for fil in $dir/*; do
            if test \! -e "$targetdir/$fil"; then
                echo `logdate` deploying $fil
                mv -f `pwd`/$fil $targetdir/$dir/
            fi
        done
        waituntilquiet
    done
fi

echo `logdate` finished stepwise deploying stuff at `date`

/bin/rm -fr $intermediatedir
