package io.excalibur.metrics;

import javax.management.ObjectName;
import java.util.Map;

/**
 * MBean notification info.
 */
public class ObjectNotificationInfo {

    public ObjectNotificationInfo(
        String notificationType,
        ObjectName objectName,
        String[] propertyNames,
        Map<String, Object> propertyValues
    ) {
        this.notificationType = notificationType;
        this.objectName = objectName;
        this.propertyNames = propertyNames;
        this.propertyValues = propertyValues;
    }

    public String notificationType;
    public ObjectName objectName;
    public String[] propertyNames;
    public Map<String, Object> propertyValues;
}
