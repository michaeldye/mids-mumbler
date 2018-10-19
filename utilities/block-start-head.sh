#!/bin/bash

if [ "$#" -ne 1 ]; then
  (>&2 echo "Missing required argument: end val for remote range")
  exit 1
fi


function remotes() {

  echo $(seq 0 $1) | xargs -n1 | awk '{print "virt-"$1":688"$1}' | xargs
}


declare -a FAIL
FAIL=($(remotes $1))

while [ "${#FAIL[@]}" -ne 0 ]; do
  echo "waiting on remotes to start: ${FAIL[*]}"

  FAIL=()
  for i in $(remotes $1); do
    nc  $(echo $i | sed 's/:/ /') <<< $(printf "bogusbogusbogus") || FAIL+=("$i")
  done
  sleep 1
done

echo "Remotes started, starting mumbler head"

(export MARKOV_UI=/home/mdye/projects/ibm/mids-mumbler/ui FETCH_PRI_URIBASE="http://localhost:9652"; java -Dakka.remote.netty.tcp.hostname=virt-head -Dakka.remote.netty.tcp.port=6879 -jar /home/mdye/projects/ibm/mids-mumbler/mumbler/target/scala-2.12/mids_mumbler-assembly-0.3.0-SNAPSHOT.jar 60 0.0.0.0:9889 $(remotes $1))
