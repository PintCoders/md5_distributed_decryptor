package PasswordCrackerWorker;

import org.apache.thrift.TException;
import thrift.gen.PasswordCrackerWorkerService.PasswordCrackerWorkerService;
import java.util.concurrent.*;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static PasswordCrackerWorker.PasswordCrackerUtil.findPasswordInRange;

class TerminationChecker {
    boolean isTerminated;

    TerminationChecker() {
        isTerminated = false;
    }

    public boolean isTerminated() {
        return isTerminated;
    }

    public void setTerminated() {
        isTerminated = true;
    }
}


public class PasswordCrackerWorkerServiceHandler implements PasswordCrackerWorkerService.Iface {
    static ConcurrentHashMap<String, TerminationChecker> terminationCheckerMap = new ConcurrentHashMap<>(); //  <jobId, TerminationChecker>
    int numberOfProcessor = Runtime.getRuntime().availableProcessors();
    ExecutorService workerPool = Executors.newFixedThreadPool(numberOfProcessor);

    /*
     * The startFindPasswordInRange is called by the master.
     * Return the result if findPasswordInRange task terminates.
     */
    @Override
    public String startFindPasswordInRange(long rangeBegin, long rangeEnd, String encryptedPassword) throws TException {
        String passwordOrNull = null;
        try {
            System.out.println("IP:" + InetAddress.getLocalHost().getHostAddress() +  " Task arrived (PASSWD:" + encryptedPassword+")");

            if (!terminationCheckerMap.containsKey(encryptedPassword)) {
                terminationCheckerMap.put(encryptedPassword, new TerminationChecker());
            }

            TerminationChecker terminationChecker = terminationCheckerMap.get(encryptedPassword);
            Future<String> workerFuture = workerPool.submit(() -> findPasswordInRange(rangeBegin, rangeEnd, encryptedPassword, terminationChecker));

            //get the result using Future class
			passwordOrNull = workerFuture.get();


        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } finally {
            return passwordOrNull;
        }
    }

    /*
     * The reportTermination is called by the master.
     * it set the termination signal.
     */
    @Override
    public void reportTermination(String jobId) throws TException {
        if (terminationCheckerMap.containsKey(jobId)) {
            TerminationChecker terminationChecker = terminationCheckerMap.get(jobId);
            terminationChecker.setTerminated();
            terminationCheckerMap.remove(jobId);
        }
    }

}

