#!/bin/bash
sleep 15

echo `date "+%d.%m.%Y %H:%M:%S"` start stepwise deploying stuff

cd /opt/sling/fileinstall-docker
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p ../fileinstall/$dir
        echo `date "+%d.%m.%Y %H:%M:%S"` deploying $dir
        for fil in $dir/*; do
            if test \! -e ../fileinstall/$fil; then
                echo `date "+%d.%m.%Y %H:%M:%S"` deploying $fil
                ln $fil ../fileinstall/$dir/
            fi
        done
    sleep 10
    done
fi

cd /opt/sling/fileinstall-user
if [ "$(ls -A .)" ]; then
    for dir in */; do
        mkdir -p ../fileinstall/$dir
        echo `date "+%d.%m.%Y %H:%M:%S"` deploying $dir
        for fil in $dir/*; do
            if test \! -e ../fileinstall/$fil; then
                echo `date "+%d.%m.%Y %H:%M:%S"` deploying $fil
                ln $fil ../fileinstall/$dir/
            fi
        done
    sleep 10
    done
fi

echo `date "+%d.%m.%Y %H:%M:%S"` finished stepwise deploying stuff at `date`
