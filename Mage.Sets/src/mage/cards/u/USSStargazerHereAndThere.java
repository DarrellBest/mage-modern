package mage.cards.u;

import mage.abilities.Ability;
import mage.abilities.common.AttacksTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.StationAbility;
import mage.abilities.keyword.StationLevelAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TargetController;
import mage.game.Game;
import mage.game.permanent.token.SpacecraftIllusionToken;

import java.util.UUID;

public final class USSStargazerHereAndThere extends CardImpl {

    public USSStargazerHereAndThere(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{3}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.SPACECRAFT);

        // Station
        this.addAbility(new StationAbility());

        // STATION 6+
        // Flying
        // Picard Maneuver -- Whenever U.S.S. Stargazer attacks, create a tapped and attacking 4/3 colorless Spacecraft
        // Illusion artifact creature token with flying. Exile that token at end of combat.
        // 4/3
        this.addAbility(new StationLevelAbility(6)
                .withLevelAbility(FlyingAbility.getInstance())
                .withLevelAbility(new AttacksTriggeredAbility(new USSStargazerHereAndThereEffect()).withFlavorWord("Picard Maneuver"))
                .withPT(4, 3));
    }

    private USSStargazerHereAndThere(final USSStargazerHereAndThere card) {
        super(card);
    }

    @Override
    public USSStargazerHereAndThere copy() {
        return new USSStargazerHereAndThere(this);
    }
}

class USSStargazerHereAndThereEffect extends OneShotEffect {

    USSStargazerHereAndThereEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "create a tapped and attacking 4/3 colorless Spacecraft Illusion artifact creature token with flying. "
                + "Exile that token at end of combat";
    }

    private USSStargazerHereAndThereEffect(final USSStargazerHereAndThereEffect effect) {
        super(effect);
    }

    @Override
    public USSStargazerHereAndThereEffect copy() {
        return new USSStargazerHereAndThereEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        CreateTokenEffect effect = new CreateTokenEffect(new SpacecraftIllusionToken(), 1, true, true);
        effect.apply(game, source);
        effect.removeTokensCreatedAt(game, source, true, PhaseStep.END_COMBAT, TargetController.ANY);
        return true;
    }
}
