package mage.cards.j;

import mage.MageInt;
import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.DiscardSourceCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.abilities.triggers.BeginningOfFirstMainTriggeredAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetCreaturePermanent;

import java.util.UUID;

public final class JadziaDaxLivingLegacy extends CardImpl {

    public JadziaDaxLivingLegacy(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{G}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.TRILL);
        this.subtype.add(SubType.SCIENTIST);
        this.power = new MageInt(0);
        this.toughness = new MageInt(1);

        // At the beginning of your first main phase, put a +1/+1 counter on Jadzia Dax, then add an amount of {G}
        // equal to the number of counters on her.
        this.addAbility(new BeginningOfFirstMainTriggeredAbility(new JadziaDaxLivingLegacyEffect()));

        // Transfer Symbiont -- {2}{G}, Discard this card: Put two +1/+1 counters on target creature.
        Ability ability = new SimpleActivatedAbility(Zone.HAND,
                new AddCountersTargetEffect(CounterType.P1P1.createInstance(2)), new ManaCostsImpl<>("{2}{G}"));
        ability.addCost(new DiscardSourceCost());
        ability.addTarget(new TargetCreaturePermanent());
        this.addAbility(ability.withFlavorWord("Transfer Symbiont"));
    }

    private JadziaDaxLivingLegacy(final JadziaDaxLivingLegacy card) {
        super(card);
    }

    @Override
    public JadziaDaxLivingLegacy copy() {
        return new JadziaDaxLivingLegacy(this);
    }
}

class JadziaDaxLivingLegacyEffect extends OneShotEffect {

    JadziaDaxLivingLegacyEffect() {
        super(Outcome.PutManaInPool);
        staticText = "put a +1/+1 counter on {this}, then add an amount of {G} equal to the number of counters on her";
    }

    private JadziaDaxLivingLegacyEffect(final JadziaDaxLivingLegacyEffect effect) {
        super(effect);
    }

    @Override
    public JadziaDaxLivingLegacyEffect copy() {
        return new JadziaDaxLivingLegacyEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Permanent jadzia = source.getSourcePermanentIfItStillExists(game);
        if (controller == null || jadzia == null) {
            return false;
        }
        jadzia.addCounters(CounterType.P1P1.createInstance(), source.getControllerId(), source, game);
        int counters = jadzia.getCounters(game).getTotalCount();
        if (counters > 0) {
            controller.getManaPool().addMana(Mana.GreenMana(counters), game, source);
        }
        return true;
    }
}
