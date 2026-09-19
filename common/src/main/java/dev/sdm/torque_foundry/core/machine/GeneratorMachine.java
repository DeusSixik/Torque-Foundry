package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * РСЃС‚РѕС‡РЅРёРє РјРµС…Р°РЅРёС‡РµСЃРєРѕР№ СЌРЅРµСЂРіРёРё: РЅРµ РёРјРµРµС‚ РІС…РѕРґРѕРІ,
 * РІС‹РґР°С‘С‚ РјРѕС‰РЅРѕСЃС‚СЊ РЅР° РІСЃРµ РіРѕСЂРёР·РѕРЅС‚Р°Р»СЊРЅС‹Рµ РіСЂР°РЅРё.
 */
public class GeneratorMachine extends MechanicalMachine {

    private final RotationalPower output;

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.output = RotationalPower.fromRaw(outputSpeedRaw, outputTorqueRaw, direction);
    }

    @Override
    public RotationalPower getOutput() {
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

