package mage.player.ai.kanna;

/**
 * One entry in the shortlist shown to the model.
 *
 * @author Darrell Best
 */
public final class RankedAction {

    public final String id;
    public final String label;
    public final String reason;
    public final int score;

    public RankedAction(String id, String label, String reason, int score) {
        this.id = id;
        this.label = label;
        this.reason = reason;
        this.score = score;
    }
}
