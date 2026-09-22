package com.eonmux.excalibur.metrics;

import javax.management.ObjectName;
import java.util.Map;

/**
 * MBean notification info.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. Instances should not be modified after creation.
 * The public fields are directly accessible and modifications are not synchronized.</p>
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
