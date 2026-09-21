package dev.sdm.torque_foundry.renderEngine;

import static org.lwjgl.opengl.GL33.*;

/**
 * В будущем GPU Instance нужно сделать
 */
@Deprecated
public final class GLRenderEngine {

    private static int vao, vbo, instanceVbo, ebo;
    private static int instanceCount;

    public static void initialize(int maxInstances) {
        instanceCount = maxInstances;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
    }
}
