import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Test {
    public static void main(String[] args) throws InterruptedException {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.shutdown();
        System.out.println("After shutdown, isShutdown=" + executor.isShutdown());
        System.out.println("After shutdown, isTerminated=" + executor.isTerminated());
        executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("After awaitTermination, isTerminated=" + executor.isTerminated());
    }
}
