package com.eonmux.excalibur.ocr.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * The model set this port of Indra OCR was built and tested against: revision {@code v1.1.0} of
 * the Hugging Face repository {@code datalab-to/indra-ocr}, the revision Indra OCR 1.1.2 itself
 * downloads.
 *
 * <p>Every file is pinned by length and SHA-256 digest, so a download is accepted only when it
 * is byte for byte the file this code was tested with, wherever it came from.</p>
 *
 * <h2>Licence</h2>
 * <p>The weights are not Apache 2.0 software. They are published under the
 * {@value #LICENSE_NAME}, whose terms restrict who may use them and for what, and bind whoever
 * downloads and uses them. Its text is downloaded with them, as {@value #LICENSE_FILE}.</p>
 */
public final class IndraModels {

    /** The repository the models are published in. */
    public static final String REPOSITORY = "datalab-to/indra-ocr";

    /** The pinned revision, a tag of {@link #REPOSITORY}. */
    public static final String REVISION = "v1.1.0";

    /** Where the files are downloaded from: the revision's file URLs share this prefix. */
    public static final URI SOURCE = URI.create("https://huggingface.co/" + REPOSITORY
        + "/resolve/" + REVISION + "/");

    /** The licence the weights are published under. */
    public static final String LICENSE_NAME = "AI Pubs Open RAIL-M License (Modified)";

    /** The name of the licence's text in the model directory. */
    public static final String LICENSE_FILE = "LICENSE";

    /** Where the licence can be read before anything is downloaded. */
    public static final URI LICENSE_URL = URI.create("https://huggingface.co/" + REPOSITORY
        + "/blob/" + REVISION + "/" + LICENSE_FILE);

    private static final List<ModelFile> CORE = List.of(
        new ModelFile(LICENSE_FILE, 14_750,
            "50c88a018a7ee322e89f6b09401b237beb0cd331685879405b3db48993950a5f"),
        new ModelFile("meta.json", 211_869,
            "4ce4c94b46f5d644033c02e718322014ff4081270f385038c571912b56696339"),
        new ModelFile("det.onnx", 389_948,
            "f2bd4cba23e9c328cf7ec824634e423e191a2c6aacbbf35c62bd79dd70f62f59"),
        new ModelFile("orient.onnx", 371_802,
            "20eaf670f55a7cf9facfe1bdd6c7b8e40be75bbf4d48a3df43f66aa3bbd874fd"),
        new ModelFile("script.onnx", 191_630,
            "b6ce4e926e19c243faaf37bd46880398bf2cb4ae56a29e1177a3b017c1280e36"),
        new ModelFile("rec.onnx", 5_042_161,
            "475972a2710be4a3cbfeb2bc7f3624ffd563de07de42476fc7a290d4e1ece484"),
        new ModelFile("rec_cjk.onnx", 10_176_018,
            "4a035516a17e62cdf33a4fe2d88077654cb8841cff024685cdef4e351694ef50"),
        new ModelFile("rec_rtl.onnx", 4_812_710,
            "468796bd684486a8388c30c8b4ed2a26e3eeb1f8ff02831c24c2e12c79a2a834"),
        new ModelFile("rec_world.onnx", 5_627_990,
            "c8d1e59f21b04966f179d2d2d8e22b230d22110b3f89855bbbf2624a58c40043"),
        new ModelFile("order.onnx", 2_973_138,
            "a42db2e8b536ad3d84045a4b7f9b0a72667cf8be4aa02d63f7b38004719fb180"),
        new ModelFile("lm.npz", 15_695_957,
            "6c0fb618a363a0acdd05d4f5b0edd9e8146d2d990541b87f6130bbfe17cea58d"),
        new ModelFile("lm_world.npz", 11_641_215,
            "34df5e74a2fcbb9cf5e7f360a559375ffa2e04d2d2943a42308c177bcb444d5d"),
        new ModelFile("lm_cjk.npz", 18_955_160,
            "6d64f13f4f90f4e529bd63340c6df76238d007feaef2abb903d8cd28d92bc2f3"));

    private static final List<ModelFile> MATH = List.of(
        new ModelFile("math_meta.json", 7_797,
            "85d9715fb8a77791b55ef89f57fb41e72eb3170ec58a7c3bee3b394268bf2b10"),
        new ModelFile("mathcls.onnx", 191_005,
            "818b1556e9ef337958db6be0c9791299d3f74a4575586b0cd469aaae9534ff96"),
        new ModelFile("math_enc.onnx", 9_889_575,
            "d4e4a6c40dad1ee988d32c6403b1590ca42864d4483bbe1e671c5dbb999a5a97"),
        new ModelFile("math_dec_init.onnx", 1_356_364,
            "919417fde9962c3318aeb478c1fe3955dd2bb8cd34476c68debed2ba39e5dcd6"),
        new ModelFile("math_dec_step.onnx", 8_429_418,
            "5ff6e6cbaf0dea70314f66222a1469736a8c5e7994fab06a5b4e53c90469239f"));

    private IndraModels() {
        throw new AssertionError("Static methods only");
    }

    /**
     * The files a conversion needs.
     *
     * @param math Whether formulas are read too, which takes five more models
     * @return The files, the licence first
     */
    public static List<ModelFile> files(boolean math) {
        if (!math) {
            return CORE;
        }
        List<ModelFile> all = new ArrayList<>(CORE);
        all.addAll(MATH);
        return List.copyOf(all);
    }
}
