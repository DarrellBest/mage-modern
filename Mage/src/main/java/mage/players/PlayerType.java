package mage.players;

/**
 * Server: all possible player types
 * <p>
 * Warning, do not change description - it must be same with config.xml file
 *
 * @author IGOUDT
 */
public enum PlayerType {
    HUMAN("Human", false, true),
    COMPUTER_DRAFT_BOT("Computer - draftbot", true, false),
    COMPUTER_MONTE_CARLO("Computer - monte carlo", true, true),
    COMPUTER_MAD("Computer - mad", true, true),
    // DARRELLBEST-FORK (keep on merge/rebase from upstream): registers our Mage.Player.AI.Kanna
    // plugin (LLM tool-calling via Ollama) as a selectable AI type in config.xml/the client dropdown.
    COMPUTER_KANNA("Computer - kanna", true, true);

    final String description;
    final boolean isAI;
    final boolean isWorkablePlayer; // false for draft bots cause it does nothing in real game and just loose a game

    PlayerType(String description, boolean isAI, boolean isWorkablePlayer) {
        this.description = description;
        this.isAI = isAI;
        this.isWorkablePlayer = isWorkablePlayer;
    }

    @Override
    public String toString() {
        return description;
    }

    public boolean isAI() {
        return this.isAI;
    }

    public boolean isWorkablePlayer() {
        return this.isWorkablePlayer;
    }

    public static PlayerType getByDescription(String description) {
        for (PlayerType type : values()) {
            if (type.description.equals(description)) {
                return type;
            }
        }
        throw new IllegalArgumentException(String.format("PlayerType (%s) is not configured in server's config.xml", description));
    }
}
