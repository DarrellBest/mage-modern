package mage.cards.t;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.ActivateAsSorceryActivatedAbility;
import mage.abilities.common.DiesSourceTriggeredAbility;
import mage.abilities.costs.common.ExileSourceFromGraveCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.abilities.effects.common.ExileTopXMayPlayUntilEffect;
import mage.abilities.keyword.HasteAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.game.Game;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class TashaYarOutOfTime extends CardImpl {

    public TashaYarOutOfTime(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(1);

        // Haste
        this.addAbility(HasteAbility.getInstance());

        // When Tasha Yar dies, exile the top card of your library. Until the end of your next turn, you may play that card.
        this.addAbility(new DiesSourceTriggeredAbility(new ExileTopXMayPlayUntilEffect(1, Duration.UntilEndOfYourNextTurn)));

        // {2}{R}, Exile this card from your graveyard: Create a token that's a copy of this card. Sacrifice it at the
        // beginning of the next end step. Activate only as a sorcery.
        Ability ability = new ActivateAsSorceryActivatedAbility(Zone.GRAVEYARD, new TashaYarOutOfTimeEffect(),
                new ManaCostsImpl<>("{2}{R}"));
        ability.addCost(new ExileSourceFromGraveCost());
        this.addAbility(ability);
    }

    private TashaYarOutOfTime(final TashaYarOutOfTime card) {
        super(card);
    }

    @Override
    public TashaYarOutOfTime copy() {
        return new TashaYarOutOfTime(this);
    }
}

class TashaYarOutOfTimeEffect extends OneShotEffect {

    TashaYarOutOfTimeEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "create a token that's a copy of this card. Sacrifice it at the beginning of the next end step";
    }

    private TashaYarOutOfTimeEffect(final TashaYarOutOfTimeEffect effect) {
        super(effect);
    }

    @Override
    public TashaYarOutOfTimeEffect copy() {
        return new TashaYarOutOfTimeEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Card card = game.getCard(source.getSourceId());
        if (card == null) {
            return false;
        }
        CreateTokenCopyTargetEffect effect = new CreateTokenCopyTargetEffect();
        effect.setTargetPointer(new FixedTarget(card, game));
        effect.apply(game, source);
        effect.sacrificeTokensCreatedAtNextEndStep(game, source);
        return true;
    }
}
