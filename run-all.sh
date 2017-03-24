#!/bin/bash

trap kill-all SIGHUP SIGINT SIGTERM EXIT  # Always call cleanup

MASTER_PORT=18000
WORKER_PORT=17000
CLIENT=10.20.13.123
MASTER=10.20.13.123
NODES=( 10.20.13.124 10.20.13.125 10.20.13.126 10.20.13.127 10.20.13.128 10.20.13.129 10.20.13.130 10.20.13.131)

CLASSPATH=`find $(pwd) -name "*.jar" -printf ":%p"`

declare -A PIDS
declare -A KEYS


function run-all() 
{
  ssh -t -t $MASTER java -cp $CLASSPATH PasswordCrackerMaster.PasswordCrackerMasterMain $MASTER_PORT &
  PIDS["master"]="$!"
  sleep 1
  for node in ${NODES[@]}; do
    ssh -t -t $node java -cp $CLASSPATH PasswordCrackerWorker.PasswordCrackerWorkerMain $MASTER $MASTER_PORT $WORKER_PORT &
    PIDS["nodes"]="$! ${PIDS["nodes"]}"
  done
  sleep 2
}

function kill-all() 
{
  read -a clients <<< ${PIDS["clients"]}

  for client in ${clients[@]}; do
    wait $client
  done
  kill ${PIDS[@]}
}

function generate-keys()
{
  for i in {1..9}; do 
    key=${i}zzzzz
    KEYS["$key"]=`echo -n $key | openssl md5 -r | cut -d ' ' -f1`
  done

}

function launch-client
{
  encryptedKey=${KEYS["$1"]}
  echo "Submiting key $1:$encryptedKey"
  java -cp $CLASSPATH PasswordCrackerClient.PasswordCrackerClient $MASTER $MASTER_PORT ${KEYS[$1]} &
  PIDS["clients"]="$! ${PIDS["clients"]}"
}

# ---- Initialize

generate-keys
run-all

# ----

# Time 0
declare -a key
read -a key <<< ${!KEYS[@]}

echo "Time 0"
launch-client ${key[1]}
launch-client ${key[2]}

#sleep 5  # time 1
#echo "Time 1"
#kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f5`  # Node 5 dies
#
#sleep 5  # time 2
#echo "Time 2"
#kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f4`  # Node 5 dies
#kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f6`  # Node 5 dies
#
#sleep 5 # time 3
#echo "Time 3"
#launch-client ${key[3]}
#launch-client ${key[4]}

#wait for all
wait 
# ---- Cleanup

kill-all
