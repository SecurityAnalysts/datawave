package datawave.microservice.query.executor;

import datawave.microservice.query.config.QueryProperties;
import datawave.microservice.query.executor.action.Create;
import datawave.microservice.query.executor.action.ExecutorAction;
import datawave.microservice.query.executor.action.Next;
import datawave.microservice.query.executor.action.Plan;
import datawave.microservice.query.executor.config.ExecutorProperties;
import datawave.microservice.query.logic.QueryLogicFactory;
import datawave.microservice.query.remote.QueryRequest;
import datawave.microservice.query.remote.QueryRequestHandler;
import datawave.microservice.query.storage.QueryQueueManager;
import datawave.microservice.query.storage.QueryStorageCache;
import datawave.microservice.query.storage.QueryTask;
import datawave.microservice.query.storage.TaskKey;
import datawave.microservice.query.storage.TaskStates;
import org.apache.accumulo.core.client.Connector;
import org.apache.log4j.Logger;
import org.springframework.cloud.bus.BusProperties;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This class holds the business logic for handling a task notification
 *
 * TODO: Query Metrics
 **/
public class QueryExecutor implements QueryRequestHandler {
    private static final Logger log = Logger.getLogger(QueryExecutor.class);
    
    protected final BlockingQueue<Runnable> workQueue;
    protected final Set<Runnable> working;
    protected final Connector connector;
    protected final QueryStorageCache cache;
    protected final QueryQueueManager queues;
    protected final QueryLogicFactory queryLogicFactory;
    protected final ExecutorProperties executorProperties;
    protected final QueryProperties queryProperties;
    protected final BusProperties busProperties;
    protected final ThreadPoolExecutor threadPool;
    protected final ApplicationContext publisher;
    
    public QueryExecutor(ExecutorProperties executorProperties, QueryProperties queryProperties, BusProperties busProperties, Connector connector,
                    QueryStorageCache cache, QueryQueueManager queues, QueryLogicFactory queryLogicFactory, ApplicationContext publisher) {
        this.executorProperties = executorProperties;
        this.queryProperties = queryProperties;
        this.busProperties = busProperties;
        this.cache = cache;
        this.queues = queues;
        this.connector = connector;
        this.queryLogicFactory = queryLogicFactory;
        this.publisher = publisher;
        this.workQueue = new LinkedBlockingDeque<>(executorProperties.getMaxQueueSize());
        this.working = Collections.synchronizedSet(new HashSet<>());
        threadPool = new ThreadPoolExecutor(executorProperties.getCoreThreads(), executorProperties.getMaxThreads(), executorProperties.getKeepAliveMs(),
                        TimeUnit.MILLISECONDS, workQueue) {
            @Override
            protected void beforeExecute(Thread t, Runnable r) {
                working.add(r);
            }
            
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                working.remove(r);
            }
        };
    }
    
    private void removeFromWorkQueue(String queryId) {
        List<Runnable> removals = new ArrayList<Runnable>();
        for (Runnable action : workQueue) {
            if (((ExecutorAction) action).getTaskKey().getQueryId().equals(queryId)) {
                removals.add(action);
            }
        }
        for (Runnable action : removals) {
            threadPool.remove(action);
        }
    }
    
    private void interruptWork(String queryId) {
        synchronized (working) {
            for (Runnable action : working) {
                if (((ExecutorAction) action).getTaskKey().getQueryId().equals(queryId)) {
                    ((ExecutorAction) action).interrupt();
                }
            }
        }
    }
    
    @Override
    public void handleRemoteRequest(QueryRequest queryRequest) {
        handleRemoteRequest(queryRequest, false);
    }
    
    public void handleRemoteRequest(QueryRequest queryRequest, boolean wait) {
        final String queryId = queryRequest.getQueryId();
        final QueryRequest.Method action = queryRequest.getMethod();
        // A close request waits for the current page to finish
        switch (action) {
            case CLOSE:
                removeFromWorkQueue(queryId);
                break;
            case CANCEL:
                removeFromWorkQueue(queryId);
                interruptWork(queryId);
                break;
            default: {
                // get the query states from the cache
                TaskStates taskStates = cache.getTaskStates(queryId);
                Map<TaskStates.TASK_STATE,Set<TaskKey>> taskStateMap = taskStates.getTaskStates();
                TaskKey taskKey = null;
                if (taskStateMap.containsKey(TaskStates.TASK_STATE.READY)) {
                    for (TaskKey key : taskStateMap.get(TaskStates.TASK_STATE.READY)) {
                        if (key.getAction() == queryRequest.getMethod()) {
                            taskKey = key;
                            break;
                        }
                    }
                }
                
                if (taskKey != null) {
                    QueryTask task = cache.getTask(taskKey);
                    ExecutorAction runnable = null;
                    switch (action) {
                        case CREATE:
                            runnable = new Create(executorProperties, queryProperties, busProperties, connector, cache, queues, queryLogicFactory, publisher,
                                            task);
                            break;
                        case NEXT:
                            runnable = new Next(executorProperties, queryProperties, busProperties, connector, cache, queues, queryLogicFactory, publisher,
                                            task);
                            break;
                        case PLAN:
                            runnable = new Plan(executorProperties, queryProperties, busProperties, connector, cache, queues, queryLogicFactory, publisher,
                                            task);
                            break;
                        default:
                            throw new UnsupportedOperationException(task.getTaskKey().toString());
                    }
                    
                    if (wait) {
                        runnable.run();
                    } else {
                        threadPool.execute(runnable);
                    }
                }
            }
        }
    }
}