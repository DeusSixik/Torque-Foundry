package dev.sdm.torque_foundry.core.client.render.structs;

import dev.team_argentum.ga_utils.api.Struct;

//@Struct(backend = Struct.Backend.FLATTEN)
public class Quad {

    // min & max 4
    public Vertex[] vertices;
    public boolean invertNormal;
}
