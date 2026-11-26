package com.laert.qrscanner;

import androidx.lifecycle.ViewModel;
public class BatchViewModel extends ViewModel {
    final BatchList list = new BatchList();
    final ScanDebouncer debouncer = new ScanDebouncer(1200);
    volatile boolean active = false;
    String last = "";
}
