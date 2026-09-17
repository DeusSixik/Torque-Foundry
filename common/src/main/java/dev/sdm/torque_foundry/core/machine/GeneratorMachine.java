package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import net.minecraft.core.Direction;

/**
 * РСЃС‚РѕС‡РЅРёРє РјРµС…Р°РЅРёС‡РµСЃРєРѕР№ СЌРЅРµСЂРіРёРё: РЅРµ РёРјРµРµС‚ РІС…РѕРґРѕРІ,
 * РІС‹РґР°С‘С‚ РјРѕС‰РЅРѕСЃС‚СЊ РЅР° РІСЃРµ РіРѕСЂРёР·РѕРЅС‚Р°Р»СЊРЅС‹Рµ РіСЂР°РЅРё.
 */
public class GeneratorMachine extends MechanicalMachine {

    private final MechanicalPower output;

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction) {
        super(MechanicalPower.fromRaw(0, 0), (byte) -1);
        this.output = MechanicalPower.fromRaw(outputSpeedRaw, outputTorqueRaw, direction);
    }

    @Override
    public MechanicalPower getOutput() {
        return output;
    }

    @Override
    protected void createDirections() {
        port(Direction.NORTH, PortRole.OUTPUT);
        port(Direction.SOUTH, PortRole.OUTPUT);
        port(Direction.WEST, PortRole.OUTPUT);
        port(Direction.EAST, PortRole.OUTPUT);
    }
}

