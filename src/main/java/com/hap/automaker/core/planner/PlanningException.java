package com.hap.automaker.core.planner;

/**
 * 规划器异常
 */
public class PlanningException extends RuntimeException {

    private final String plannerName;
    private final String errorCode;

    public PlanningException(String plannerName, String message) {
        super(message);
        this.plannerName = plannerName;
        this.errorCode = "PLANNING_ERROR";
    }

    public PlanningException(String plannerName, String message, Throwable cause) {
        super(message, cause);
        this.plannerName = plannerName;
        this.errorCode = "PLANNING_ERROR";
    }

    public PlanningException(String plannerName, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.plannerName = plannerName;
        this.errorCode = errorCode;
    }

    public String getPlannerName() { return plannerName; }
    public String getErrorCode() { return errorCode; }

    @Override
    public String toString() {
        return String.format("PlanningException[%s:%s]: %s", plannerName, errorCode, getMessage());
    }
}
