package PasswordCrackerMaster;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.async.TAsyncClientManager;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TNonblockingSocket;
import org.apache.thrift.transport.TSocket;
import thrift.gen.PasswordCrackerMasterService.PasswordCrackerMasterService;
import thrift.gen.PasswordCrackerWorkerService.PasswordCrackerWorkerService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static PasswordCrackerMaster.PasswordCrackerConts.SUB_RANGE_SIZE;
import static PasswordCrackerMaster.PasswordCrackerConts.WORKER_PORT;
import static PasswordCrackerMaster.PasswordCrackerConts.NUMBER_OF_WORKER;
import static PasswordCrackerMaster.PasswordCrackerMasterServiceHandler.jobInfoMap;
import static PasswordCrackerMaster.PasswordCrackerMasterServiceHandler.workersAddressList;

public class PasswordCrackerMasterServiceHandler implements PasswordCrackerMasterService.Iface {
    public static List<TSocket> workersSocketList = new LinkedList<>();  //Connected Socket
    public static List<String> workersAddressList = new LinkedList<>(); // Connected WorkerAddress
    public static ConcurrentHashMap<String, PasswordDecrypterJob> jobInfoMap = new ConcurrentHashMap<>();
    public static ConcurrentHashMap<String, Long> latestHeartbeatInMillis = new ConcurrentHashMap<>(); // <workerAddress, time>
    public static ExecutorService workerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    public static ScheduledExecutorService heartBeatCheckPool = Executors.newScheduledThreadPool(1);
    public static ConcurrentHashMap<Integer, List<PasswordTask>> taskMap = new ConcurrentHashMap<>();

    /*
     * The decrypt method create the job and put the job with jobId (encrypted Password) in map.
     * And call the requestFindPassword and if it finds the password, it return the password to the client.
     */
    @Override
    public String decrypt(String encryptedPassword) throws TException {
        PasswordDecrypterJob decryptJob = new PasswordDecrypterJob();
        jobInfoMap.put(encryptedPassword, decryptJob);
        requestFindPassword(encryptedPassword, 0l, SUB_RANGE_SIZE);

        return decryptJob.getPassword(); 
    }

    /*
     * The reportHeartBeat receives the Heartbeat from workers.
     * Consider the checkHeartBeat method and use latestHeartbeatInMillis map.
    */
    @Override
    public void reportHeartBeat(String workerAddress)
            throws TException {
            latestHeartbeatInMillis.put(workerAddress, System.currentTimeMillis());
    }

    /*
     * The requestFindPassword requests workers to find password using RPC in asynchronous way.
    */
    public static void requestFindPassword(String encryptedPassword, long rangeBegin, long subRangeSize) {
        PasswordCrackerWorkerService.AsyncClient worker = null;
        FindPasswordMethodCallback findPasswordCallBack = new FindPasswordMethodCallback(encryptedPassword);
        try {
            for (int taskId = 0; taskId < NUMBER_OF_WORKER; taskId++) {
                int workerId = taskId % workersAddressList.size();
                String workerAddress = workersAddressList.get(workerId);

                long subRangeBegin = rangeBegin + (taskId * subRangeSize);
                long subRangeEnd = subRangeBegin + subRangeSize;

                worker = new PasswordCrackerWorkerService.AsyncClient(new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(workerAddress, WORKER_PORT));
                worker.startFindPasswordInRange(subRangeBegin, subRangeEnd, encryptedPassword, findPasswordCallBack);

                List<PasswordTask> listOfTasks = new LinkedList<>();
                taskMap.putIfAbsent(workerId, listOfTasks);
                taskMap.get(workerId).add(new PasswordTask(subRangeBegin, subRangeEnd, workerId, encryptedPassword));
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        catch (TException e) {
            e.printStackTrace();
        }
    }

    /*
     * The redistributeFailedTask distributes the dead workers's job (or a set of possible password) to active workers.
     *
     * Check the checkHeartBeat method
     */
    public static void redistributeFailedTask(ArrayList<Integer> failedWorkerIdList) {

        for (Integer workerIdInteger : failedWorkerIdList) {
            workersAddressList.remove(workerIdInteger.intValue());
        }

        // For each of the jobs 
        PasswordCrackerWorkerService.AsyncClient worker = null;
        for (Integer workerIdInteger : failedWorkerIdList) {
            for (PasswordTask task : taskMap.get(workerIdInteger.intValue())) {
                try {
                    int workerId = task.workerId % workersAddressList.size();
                    task.workerId = workerId;

                    FindPasswordMethodCallback findPasswordCallBack = new FindPasswordMethodCallback(task.encryptedPassword);
                    worker = new PasswordCrackerWorkerService.AsyncClient(
                            new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(workersAddressList.get(workerId), WORKER_PORT));
                    worker.startFindPasswordInRange(task.lowerBoundary, task.upperBoundary, task.encryptedPassword, findPasswordCallBack);
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
                catch (TException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /*
     *  If the master didn't receive the "HeartBeat" in 5 seconds from any workers,
     *  it considers the workers that didn't send the "HeartBeat" as dead.
     *  And then, it redistributes the dead worker's job in other alive workers
     *
     *  hint : use latestHeartbeatinMillis, workersAddressList
     *
     *  you must think about when several workers is dead.
     *
     *  and use the workerPool
     */
    public static void checkHeartBeat() {
        /** COMPLETE **/
        int workerId = 0;
        final long thresholdAge = 5_000;
        long currentTime = System.currentTimeMillis();

        ArrayList<Integer> failedWorkerIdList = new ArrayList<>();

        for (String addr : workersAddressList) {
          long originTime = latestHeartbeatInMillis.get(addr);
          long timeElapsed = currentTime - originTime;
          if (timeElapsed > thresholdAge) {
            failedWorkerIdList.add(workerId);
            System.out.println("Lost Worker [Addr " + addr + " : time " + timeElapsed + "]");
          }

          workerId++;
        }
        redistributeFailedTask(failedWorkerIdList);
    }
}

//CallBack
class FindPasswordMethodCallback implements AsyncMethodCallback<PasswordCrackerWorkerService.AsyncClient.startFindPasswordInRange_call> {
    private String jobId;

    FindPasswordMethodCallback(String jobId) {
        this.jobId = jobId;
    }

    /*
     *  if the returned result from worker is not null, it completes the job.
     *  and call the jobTermination method
     */
    @Override
    public void onComplete(PasswordCrackerWorkerService.AsyncClient.startFindPasswordInRange_call startFindPasswordInRange_call) {
        try {
            String findPasswordResult = startFindPasswordInRange_call.getResult();
            /** COMPLETE **/

            if (findPasswordResult != null) {
                jobTermination(jobId);
                PasswordDecrypterJob futureJob = jobInfoMap.get(jobId);
                futureJob.setPassword(findPasswordResult);
            }

        }
        catch (TException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onError(Exception e) {
        System.out.println("Error : startFindPasswordInRange of FindPasswordMethodCallback");
    }

    /*
     *  The jobTermination transfer the termination signal to workers in asynchronous way
     */
    private void jobTermination(String jobId) {
        try {
            PasswordCrackerWorkerService.AsyncClient worker = null;
            for (String workerAddress : workersAddressList) {
                worker = new PasswordCrackerWorkerService.
                        AsyncClient(new TBinaryProtocol.Factory(), new TAsyncClientManager(), new TNonblockingSocket(workerAddress, WORKER_PORT));
                /** COMPLETE **/

                worker.reportTermination(jobId, new AsyncMethodCallback<PasswordCrackerWorkerService.AsyncClient.reportTermination_call>() {
                  @Override
                  public void onComplete(PasswordCrackerWorkerService.AsyncClient.reportTermination_call termination) {
                      jobInfoMap.remove(jobId);
                  }

                  @Override
                  public void onError(Exception e) {
                    System.out.println("Error : reportTermination " + e.getMessage());
                  }
                
                });
            }
        }
        catch (TException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
}


class PasswordTask {
        public final long lowerBoundary;
        public final long upperBoundary;
        public final String encryptedPassword;

        public int workerId;
        PasswordTask(long lowerBoundary, long upperBoundary, int workerId, String encryptedPassword) {
            this.lowerBoundary = lowerBoundary;
            this.upperBoundary = upperBoundary;
            this.workerId = workerId;
            this.encryptedPassword = encryptedPassword;
        }
    }
