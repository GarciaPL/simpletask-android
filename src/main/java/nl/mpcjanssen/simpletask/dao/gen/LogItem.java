package nl.mpcjanssen.simpletask.dao.gen;

// THIS CODE IS GENERATED BY greenDAO, DO NOT EDIT. Enable "keep" sections if you want to edit. 
/**
 * Entity mapped to table "LOG_ITEM".
 */
public class LogItem {

    /** Not-null value. */
    private java.util.Date timestamp;
    /** Not-null value. */
    private String severity;
    /** Not-null value. */
    private String tag;
    /** Not-null value. */
    private String message;
    /** Not-null value. */
    private String exception;

    public LogItem() {
    }

    public LogItem(java.util.Date timestamp, String severity, String tag, String message, String exception) {
        this.timestamp = timestamp;
        this.severity = severity;
        this.tag = tag;
        this.message = message;
        this.exception = exception;
    }

    /** Not-null value. */
    public java.util.Date getTimestamp() {
        return timestamp;
    }

    /** Not-null value; ensure this value is available before it is saved to the database. */
    public void setTimestamp(java.util.Date timestamp) {
        this.timestamp = timestamp;
    }

    /** Not-null value. */
    public String getSeverity() {
        return severity;
    }

    /** Not-null value; ensure this value is available before it is saved to the database. */
    public void setSeverity(String severity) {
        this.severity = severity;
    }

    /** Not-null value. */
    public String getTag() {
        return tag;
    }

    /** Not-null value; ensure this value is available before it is saved to the database. */
    public void setTag(String tag) {
        this.tag = tag;
    }

    /** Not-null value. */
    public String getMessage() {
        return message;
    }

    /** Not-null value; ensure this value is available before it is saved to the database. */
    public void setMessage(String message) {
        this.message = message;
    }

    /** Not-null value. */
    public String getException() {
        return exception;
    }

    /** Not-null value; ensure this value is available before it is saved to the database. */
    public void setException(String exception) {
        this.exception = exception;
    }

}
