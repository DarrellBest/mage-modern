package mage.cards.s;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.common.delayed.ReflexiveTriggeredAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetArtifactPermanent;

import java.util.UUID;

public final class SevenOfNineTertiaryAdjunct extends CardImpl {

    public SevenOfNineTertiaryAdjunct(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT, CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.BORG);
        this.power = new MageInt(1);
        this.toughness = new MageInt(4);

        // That Is Irrelevant -- {T}: Draw a card, then discard a card. When you discard a nonland card this way, put
        // two charge counters on up to one target artifact.
        this.addAbility(new SimpleActivatedAbility(new SevenOfNineTertiaryAdjunctEffect(), new TapSourceCost())
                .withFlavorWord("That Is Irrelevant"));
    }

    private SevenOfNineTertiaryAdjunct(final SevenOfNineTertiaryAdjunct card) {
        super(card);
    }

    @Override
    public SevenOfNineTertiaryAdjunct copy() {
        return new SevenOfNineTertiaryAdjunct(this);
    }
}

class SevenOfNineTertiaryAdjunctEffect extends OneShotEffect {

    SevenOfNineTertiaryAdjunctEffect() {
        super(Outcome.DrawCard);
        staticText = "draw a card, then discard a card. When you discard a nonland card this way, "
                + "put two charge counters on up to one target artifact";
    }

    private SevenOfNineTertiaryAdjunctEffect(final SevenOfNineTertiaryAdjunctEffect effect) {
        super(effect);
    }

    @Override
    public SevenOfNineTertiaryAdjunctEffect copy() {
        return new SevenOfNineTertiaryAdjunctEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        controller.drawCards(1, source, game);
        Cards discarded = controller.discard(1, false, false, source, game);
        boolean nonland = discarded.getCards(game).stream().anyMatch(card -> !card.isLand(game));
        if (nonland) {
            ReflexiveTriggeredAbility reflexive = new ReflexiveTriggeredAbility(
                    new AddCountersTargetEffect(CounterType.CHARGE.createInstance(2)), false,
                    "put two charge counters on up to one target artifact");
            reflexive.addTarget(new TargetArtifactPermanent(0, 1));
            game.fireReflexiveTriggeredAbility(reflexive, source);
        }
        return true;
    }
}
