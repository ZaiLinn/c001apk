package com.zailinn.c001apk.semantic.ort;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.util.HashMap;
import java.util.Map;

/**
 * ONNX Runtime session for the embedding model.
 *
 * <p>Not part of the app's dex: the build compiles this file and ONNX Runtime's
 * Java API into a separate dex asset (semantic-runtime.jar) that the app loads
 * with a class loader pointing at the downloaded native libraries. It is plain
 * Java and shares no types with the app, which calls it by reflection.
 */
public final class OrtRuntime implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final boolean needsTypeIds;

    public OrtRuntime(String modelPath, int threads) throws OrtException {
        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = env.createSession(modelPath, options);
        }
        needsTypeIds = session.getInputNames().contains("token_type_ids");
    }

    /** {@code last_hidden_state} for a padded batch. */
    public float[][][] hiddenStates(long[][] inputIds, long[][] attentionMask) throws OrtException {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(env, inputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, attentionMask));
            if (needsTypeIds) {
                long[][] types = new long[inputIds.length][];
                for (int i = 0; i < types.length; i++) types[i] = new long[inputIds[i].length];
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, types));
            }
            try (OrtSession.Result result = session.run(inputs)) {
                return (float[][][]) result.get(0).getValue();
            }
        } finally {
            for (OnnxTensor tensor : inputs.values()) tensor.close();
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
