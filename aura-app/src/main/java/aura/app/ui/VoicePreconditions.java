package aura.app.ui;

/**
 * What enrolment and wake-word training need before they can run, and the words
 * for whichever of it is missing.
 *
 * <p>Two sections say it from the same {@code voice.status}: Voice setup beside
 * the button that is dead, and Status in the card that reports what is missing.
 * Status once carried a rule of its own, "any takes at all but no model means not
 * enough to train on", and called eight takes not enough while Voice setup had
 * Enrol live beside them. One class holding the rule is how the two stay in step.
 */
final class VoicePreconditions {

    /** Under this many wake takes, training refuses outright - training.py's number. */
    static final int WAKE_MINIMUM = 5;

    /** And this many is what it takes to be any good. */
    static final int WAKE_RECOMMENDED = 20;

    private VoicePreconditions() {
    }

    /** Whether {@code enrol} would get past its own preconditions. */
    static boolean canEnrol(int referenceTakes, boolean speakerModel) {
        return enrolBlocker(referenceTakes, speakerModel).isEmpty();
    }

    /**
     * Why {@code enrol} cannot run, or {@code ""} when it can. Enrolment averages
     * the takes through the speaker model, so it needs at least one take and the
     * model.
     */
    static String enrolBlocker(int referenceTakes, boolean speakerModel) {
        if (referenceTakes == 0) {
            return "no takes yet - record some first";
        }
        if (!speakerModel) {
            // The one precondition that is not a recording: enrolment runs the
            // takes through models/speaker.onnx, which is downloaded by hand.
            return "the speaker model is missing - see models\\speaker.onnx";
        }
        return "";
    }

    /** Whether {@code train.wake} would get past its own preconditions. */
    static boolean canTrain(int wakeTakes, boolean featureModels, int negatives) {
        return wakeTakes >= WAKE_MINIMUM && trainBlocker(featureModels, negatives).isEmpty();
    }

    /**
     * Why {@code train.wake} cannot run for any reason besides the number of takes,
     * or {@code ""} when nothing else is in the way. The take count is left to the
     * caller, because the two sections word it differently: Voice setup beside
     * the spinner that adds more, Status as the answer to "how far off am I?".
     *
     * <p>Without these two the sidecar answers {@code NO_FEATURE_MODELS} or refuses
     * for want of anything to train against, and both are visible in {@code
     * voice.status} before anyone presses.
     */
    static String trainBlocker(boolean featureModels, int negatives) {
        if (!featureModels) {
            return "openWakeWord's models are missing - see models\\openwakeword";
        }
        if (negatives == 0) {
            return "nothing to train against - no audio that is not the wake word";
        }
        return "";
    }
}
