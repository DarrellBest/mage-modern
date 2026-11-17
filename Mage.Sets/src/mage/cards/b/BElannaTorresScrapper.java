package mage.cards.b;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.keyword.SurveilEffect;
import mage.abilities.keyword.TrampleAbility;
import mage.abilities.keyword.VigilanceAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;

public final class BElannaTorresScrapper extends CardImpl {

    public BElannaTorresScrapper(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{U}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.KLINGON);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Vigilance, trample
        this.addAbility(VigilanceAbility.getInstance());
        this.addAbility(TrampleAbility.getInstance());

        // {2}, {T}: Surveil 2, then you may reveal the top card of your library. Draw a card if it's an artifact card.
        // If you don't draw a card this way, put two +1/+1 counters on B'Elanna Torres.
        Ability ability = new SimpleActivatedAbility(new BElannaTorresScrapperEffect(), new GenericManaCost(2));
        ability.addCost(new TapSourceCost());
        this.addAbility(ability);
    }

    private BElannaTorresScrapper(final BElannaTorresScrapper card) {
        super(card);
    }

    @Override
    public BElannaTorresScrapper copy() {
        return new BElannaTorresScrapper(this);
    }
}

class BElannaTorresScrapperEffect extends OneShotEffect {

    BElannaTorresScrapperEffect() {
        super(Outcome.DrawCard);
        staticText = "surveil 2, then you may reveal the top card of your library. Draw a card if it's an artifact card. "
                + "If you don't draw a card this way, put two +1/+1 counters on {this}";
    }

    private BElannaTorresScrapperEffect(final BElannaTorresScrapperEffect effect) {
        super(effect);
    }

    @Override
    public BElannaTorresScrapperEffect copy() {
        return new BElannaTorresScrapperEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        new SurveilEffect(2).apply(game, source);
        boolean drew = false;
        Card top = controller.getLibrary().getFromTop(game);
        if (top != null && controller.chooseUse(Outcome.DrawCard, "Reveal the top card of your library?", source, game)) {
            controller.revealCards(source, new CardsImpl(top), game);
            if (top.isArtifact(game)) {
                drew = controller.drawCards(1, source, game) > 0;
            }
        }
        if (!drew) {
            Permanent permanent = source.getSourcePermanentIfItStillExists(game);
            if (permanent != null) {
                permanent.addCounters(CounterType.P1P1.createInstance(2), source.getControllerId(), source, game);
            }
        }
        return true;
    }
}
