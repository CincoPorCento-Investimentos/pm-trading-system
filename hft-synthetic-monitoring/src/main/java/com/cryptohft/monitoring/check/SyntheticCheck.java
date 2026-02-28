package com.cryptohft.monitoring.check;

public interface SyntheticCheck {

    String getName();

    String getDescription();

    String getGroup();

    CheckPriority getPriority();

    CheckResult execute();
}
