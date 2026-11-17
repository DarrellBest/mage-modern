package mage.cards.j;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.AttacksCreatureYouControlTriggeredAbility;
import mage.abilities.common.EntersBattlefieldControlledTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.Cards;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SetTargetPointer;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.counters.CounterType;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.filter.predicate.mageobject.CommanderPredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;

import java.util.UUID;

public final class JaniceRandEssentialAide extends CardImpl {

    private static final FilterControlledCreaturePermanent filter = new FilterControlledCreaturePermanent("a commander you control");

    static {
        filter.add(CommanderPredicate.instance);
    }

    public JaniceRandEssentialAide(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{G}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ADVISOR);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // Whenever a commander you control enters or attacks, put a +1/+1 counter on it, then reveal cards from the
        // top of your library until you reveal a land card. Put that card onto the battlefield tapped and the rest on
        // the bottom of your library in a random order.
        this.addAbility(new EntersBattlefieldControlledTriggeredAbility(
                Zone.BATTLEFIELD, new JaniceRandEssentialAideEffect(), filter, false, SetTargetPointer.PERMANENT
        ).setTriggerPhrase("Whenever a commander you control enters or attacks, "));
        // (same rule text as the enters trigger above, so only that one is shown)
        this.addAbility(new AttacksCreatureYouControlTriggeredAbility(
                new JaniceRandEssentialAideEffect(), false, filter, true
        ).setRuleVisible(false));
    }

    private JaniceRandEssentialAide(final JaniceRandEssentialAide card) {
        super(card);
    }

    @Override
    public JaniceRandEssentialAide copy() {
        return new JaniceRandEssentialAide(this);
    }
}

class JaniceRandEssentialAideEffect extends OneShotEffect {

    JaniceRandEssentialAideEffect() {
        super(Outcome.PutLandInPlay);
        staticText = "put a +1/+1 counter on it, then reveal cards from the top of your library until you reveal a "
                + "land card. Put that card onto the battlefield tapped and the rest on the bottom of your library in a random order";
    }

    private JaniceRandEssentialAideEffect(final JaniceRandEssentialAideEffect effect) {
        super(effect);
    }

    @Override
    public JaniceRandEssentialAideEffect copy() {
        return new JaniceRandEssentialAideEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        Permanent commander = game.getPermanent(getTargetPointer().getFirst(game, source));
        if (commander != null) {
            commander.addCounters(CounterType.P1P1.createInstance(), source.getControllerId(), source, game);
        }
        Cards revealed = new CardsImpl();
        Card land = null;
        for (Card card : controller.getLibrary().getCards(game)) {
            revealed.add(card);
            if (card.isLand(game)) {
                land = card;
                break;
            }
        }
        if (revealed.isEmpty()) {
            return true;
        }
        controller.revealCards(source, revealed, game);
        if (land != null) {
            controller.moveCards(land, Zone.BATTLEFIELD, source, game, true, false, false, null);
            revealed.remove(land);
        }
        controller.putCardsOnBottomOfLibrary(revealed, game, source, false);
        return true;
    }
}
