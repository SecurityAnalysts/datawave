package datawave.microservice.common.connection;

import org.apache.accumulo.core.client.Connector;

import java.util.Map;

public interface AccumuloConnectionFactory {
    
    enum Priority {
        
        LOW, NORMAL, HIGH, ADMIN
    }
    
    enum State {
        
        WAITING, CONNECTED
    }
    
    /**
     * @param poolName
     *            the name of the pool to query
     * @return name of the user used in the connection pools
     */
    String getConnectionUserName(String poolName);
    
    /**
     * Gets a connection from the pool with the assigned priority
     *
     * Deprecated in 2.2.3, use {@link #getConnection(Priority, Map)}
     *
     * @param priority
     *            the connection's Priority
     * @param trackingMap
     *            the tracking map
     * @return accumulo connection
     * @throws Exception
     *             on failure
     */
    Connector getConnection(Priority priority, Map<String,String> trackingMap) throws Exception;
    
    /**
     * Gets a connection from the named pool with the assigned priority
     *
     * @param poolName
     *            the name of the pool to retrieve the connection from
     * @param priority
     *            the priority of the connection
     * @param trackingMap
     *            the tracking map
     * @return Accumulo connection
     * @throws Exception
     *             on failure
     */
    Connector getConnection(String poolName, Priority priority, Map<String,String> trackingMap) throws Exception;
    
    /**
     * Returns the connection to the pool with the associated priority.
     *
     * @param connection
     *            The connection to return
     * @throws Exception
     *             on failure
     */
    void returnConnection(Connector connection) throws Exception;
    
    Map<String,String> getTrackingMap(StackTraceElement[] stackTrace);
}