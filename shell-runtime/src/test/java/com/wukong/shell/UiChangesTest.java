package com.wukong.shell;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class UiChangesTest {
    @Test public void otherWindowsDoNotResetTheTargetQuietPeriod() {
        UiChanges changes = new UiChanges();
        changes.record(1, 100); changes.record(2, 900);
        assertArrayEquals(new long[]{1, 900}, changes.snapshot(Arrays.asList(1), 1000));
        assertArrayEquals(new long[]{2, 100}, changes.snapshot(Arrays.asList(1, 2), 1000));
    }
    @Test public void unknownWindowsAndOutOfOrderTimestampsAreSafe() {
        UiChanges changes = new UiChanges();
        changes.record(-1, 1000);
        assertArrayEquals(new long[]{0, 10000}, changes.snapshot(Arrays.asList(1), 1000));
        changes.record(1, 900); changes.record(1, 800);
        assertArrayEquals(new long[]{2, 100}, changes.snapshot(Arrays.asList(1), 1000));
    }
}
