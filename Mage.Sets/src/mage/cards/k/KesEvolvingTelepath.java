package mage.cards.k;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.triggers.BeginningOfEndStepTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;

public final class KesEvolvingTelepath extends CardImpl {

    public KesEvolvingTelepath(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{4}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.OCAMPA);
        this.subtype.add(SubType.ADVISOR);
        this.power = new MageInt(2);
        this.toughness = new MageInt(6);

        // At the beginning of your end step, put a time counter on Kes, then draw X cards, where X is the number
        // of time counters on her. Then if she has nine or more time counters on her, sacrifice her.
        this.addAbility(new BeginningOfEndStepTriggeredAbility(new KesEvolvingTelepathEffect()));
    }

    private KesEvolvingTelepath(final KesEvolvingTelepath card) {
        super(card);
    }

    @Override
    public KesEvolvingTelepath copy() {
        return new KesEvolvingTelepath(this);
    }
}

class KesEvolvingTelepathEffect extends OneShotEffect {

    KesEvolvingTelepathEffect() {
        super(Outcome.DrawCard);
        staticText = "put a time counter on {this}, then draw X cards, where X is the number of time counters on her. "
                + "Then if she has nine or more time counters on her, sacrifice her";
    }

    private KesEvolvingTelepathEffect(final KesEvolvingTelepathEffect effect) {
        super(effect);
    }

    @Override
    public KesEvolvingTelepathEffect copy() {
        return new KesEvolvingTelepathEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Permanent kes = source.getSourcePermanentIfItStillExists(game);
        if (controller == null || kes == null) {
            return false;
        }
        kes.addCounters(CounterType.TIME.createInstance(), source.getControllerId(), source, game);
        int x = kes.getCounters(game).getCount(CounterType.TIME);
        controller.drawCards(x, source, game);
        // re-read: she may have left or had counters changed while drawing
        kes = source.getSourcePermanentIfItStillExists(game);
        if (kes != null && kes.getCounters(game).getCount(CounterType.TIME) >= 9) {
            kes.sacrifice(source, game);
        }
        return true;
    }
}
