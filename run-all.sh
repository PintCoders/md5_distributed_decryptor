#!/bin/bash
# Options for the experiment
# 
WORKER_FILE=/home/vicente/PasswordCrackerThrift/PasswordCrackerMaster/WorkerInfoList.json
MASTER_PORT=18000
WORKER_PORT=17000
MASTER=10.20.13.123

# -----------------------------------------------------------------------
trap kill-all SIGHUP SIGINT SIGTERM EXIT  # Always call cleanup

NODES=`cat $WORKER_FILE | grep -Po '[0-9]*\..*\..*\.[0-9]*'`
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
    PIDS["nodes"]="${PIDS["nodes"]} $!"
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
sleep 1
launch-client ${key[3]}

sleep 5  # time 1
echo "Time 1"
kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f5`  # Node 5 dies

sleep 10  # time 2
echo "Time 2"
kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f4`  # Node 5 dies
kill `echo -n ${PIDS["nodes"]} | cut -d' ' -f6`  # Node 5 dies

sleep 15 # time 3
echo "Time 3"
launch-client ${key[6]}
launch-client ${key[4]}

# ---- Cleanup

kill-all
