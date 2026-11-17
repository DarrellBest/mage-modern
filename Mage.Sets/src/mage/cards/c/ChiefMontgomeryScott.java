package mage.cards.c;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.GenericManaCost;
import mage.abilities.dynamicvalue.common.DifferentlyNamedPermanentCount;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.hint.common.StrangeNewWorldsHint;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AbilityWord;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.counters.Counters;
import mage.filter.FilterCard;
import mage.filter.StaticFilters;
import mage.filter.predicate.Predicates;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetCardInHand;

import java.util.UUID;

public final class ChiefMontgomeryScott extends CardImpl {

    static final DifferentlyNamedPermanentCount LANDS = new DifferentlyNamedPermanentCount(StaticFilters.FILTER_CONTROLLED_PERMANENT_LANDS);

    public ChiefMontgomeryScott(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{G}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Strange new worlds -- {6}, {T}: You may put a creature or Spacecraft card from your hand onto the
        // battlefield. It enters with X additional +1/+1 counters on it if it's a creature and X additional charge
        // counters on it if it's a Spacecraft, where X is the number of differently named lands you control.
        Ability ability = new SimpleActivatedAbility(new ChiefMontgomeryScottEffect(), new GenericManaCost(6));
        ability.addCost(new TapSourceCost());
        this.addAbility(ability.setAbilityWord(AbilityWord.STRANGE_NEW_WORLDS).addHint(StrangeNewWorldsHint.instance));
    }

    private ChiefMontgomeryScott(final ChiefMontgomeryScott card) {
        super(card);
    }

    @Override
    public ChiefMontgomeryScott copy() {
        return new ChiefMontgomeryScott(this);
    }
}

class ChiefMontgomeryScottEffect extends OneShotEffect {

    private static final FilterCard filter = new FilterCard("creature or Spacecraft card");

    static {
        filter.add(Predicates.or(CardType.CREATURE.getPredicate(), SubType.SPACECRAFT.getPredicate()));
    }

    ChiefMontgomeryScottEffect() {
        super(Outcome.PutCardInPlay);
        staticText = "you may put a creature or Spacecraft card from your hand onto the battlefield. It enters with X "
                + "additional +1/+1 counters on it if it's a creature and X additional charge counters on it if it's "
                + "a Spacecraft, where X is the number of differently named lands you control";
    }

    private ChiefMontgomeryScottEffect(final ChiefMontgomeryScottEffect effect) {
        super(effect);
    }

    @Override
    public ChiefMontgomeryScottEffect copy() {
        return new ChiefMontgomeryScottEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null || controller.getHand().count(filter, game) == 0) {
            return false;
        }
        TargetCardInHand target = new TargetCardInHand(0, 1, filter);
        controller.choose(Outcome.PutCardInPlay, target, source, game);
        Card card = game.getCard(target.getFirstTarget());
        if (card == null) {
            return false;
        }
        int x = ChiefMontgomeryScott.LANDS.calculate(game, source, this);
        Counters counters = new Counters();
        if (x > 0 && card.isCreature(game)) {
            counters.addCounter(CounterType.P1P1.createInstance(x));
        }
        if (x > 0 && card.hasSubtype(SubType.SPACECRAFT, game)) {
            counters.addCounter(CounterType.CHARGE.createInstance(x));
        }
        if (!counters.isEmpty()) {
            game.setEnterWithCounters(card.getId(), counters);
        }
        controller.moveCards(card, Zone.BATTLEFIELD, source, game);
        return true;
    }
}
