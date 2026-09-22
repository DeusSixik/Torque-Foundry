package dev.sdm.torque_foundry.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.api.physics.GroupSnapshotView;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.physics.TestRig;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Ф2: сборка синк-payload из снапшота вместо живых машин — слоты
 * сохраняются, пустые слоты пропускаются, данные из одного тика.
 */
class SyncPayloadTest {

    @Test
    void payloadMapsSnapshotSlotsToEntries() {
        final GroupSnapshotView view = new GroupSnapshotView();
        view.ensureCapacity(3);
        view.groupId = 42;
        view.tickId = 7;
        view.machineCount = 3;
        view.netSpeedRaw = 256_000;

        // Слот 1 — дыра топологии (posLong = 0): в payload не попадает,
        // слоты 0 и 2 сохраняют свои индексы.
        view.posLong[0] = new BlockPos(1, 2, 3).asLong();
        view.posLong[1] = 0;
        view.posLong[2] = new BlockPos(4, 5, 6).asLong();
        view.states[0] = dev.sdm.torque_foundry.physics.WorkState.WORKING;
        view.states[2] = dev.sdm.torque_foundry.physics.WorkState.IDLE;
        view.receivedSpeedRaw[0] = 100_000;
        view.receivedSpeedRaw[2] = 200_000;

        final GroupSyncPayload payload = TFNetworking.buildPayload(view);

        assertEquals(42, payload.groupId());
        assertEquals(3, payload.memberCount());
        assertEquals(2, payload.entries().size(), "дыра пропущена");

        final GroupSyncPayload.Entry e0 = payload.entries().get(0);
        assertEquals(new BlockPos(1, 2, 3), e0.pos());
        assertEquals(0, e0.slot(), "слот = индекс в снапшоте");
        assertEquals(100_000, e0.speedRaw());

        final GroupSyncPayload.Entry e1 = payload.entries().get(1);
        assertEquals(2, e1.slot(), "слот дыры не смещает соседей");
        assertEquals(200_000, e1.speedRaw());
    }

    @Test
    void payloadFromGroupUsesPublishedSnapshot() {
        final var shafts = new dev.sdm.torque_foundry.physics.machine.MechanicalMachine[2];
        for (int i = 0; i < shafts.length; i++) {
            shafts[i] = TestRig.shaft(new BlockPos(1 + i, 0, 0));
        }
        final MechanicalGroup group = TestRig.line(
                TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shafts,
                TestRig.consumer(64_000, 32_000, new BlockPos(3, 0, 0)));
        MechanicalGroupManager.register(group);

        // До первого тика: ничего не публиковалось — пустой payload,
        // НЕ "группа удалена" (клиент досыл получит следующим тиком).
        GroupSyncPayload empty = TFNetworking.buildPayload(group);
        assertEquals(0, empty.entries().size());

        group.computeTick();
        final GroupSyncPayload payload = TFNetworking.buildPayload(group);
        assertEquals(group.getSimTick() >= 0, payload.memberCount() >= 0);
        assertEquals(3, payload.entries().size(), "все машины с позицией");
        assertEquals(group.getGroupId(), payload.groupId());

        // Данные payload = опубликованный снапшот (тот же тик).
        final GroupSnapshotView view = new GroupSnapshotView();
        assertTrue(group.copySnapshotTo(view, true));
        assertEquals(view.tickId > 0, !payload.entries().isEmpty());
        for (GroupSyncPayload.Entry entry : payload.entries()) {
            assertEquals(view.states[entry.slot()].ordinal(), entry.state(),
                    "состояние слота " + entry.slot());
            assertEquals(view.receivedSpeedRaw[entry.slot()], entry.speedRaw(),
                    "скорость слота " + entry.slot());
        }
    }
}
