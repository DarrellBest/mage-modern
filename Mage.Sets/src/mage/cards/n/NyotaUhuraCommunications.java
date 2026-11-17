package mage.cards.n;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.AsEntersBattlefieldAbility;
import mage.abilities.common.SpellCastOpponentTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.FilterSpell;
import mage.filter.predicate.ObjectSourcePlayer;
import mage.filter.predicate.ObjectSourcePlayerPredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackObject;
import mage.players.Player;

import java.util.UUID;

public final class NyotaUhuraCommunications extends CardImpl {

    private static final FilterSpell filter = new FilterSpell("a spell with mana value equal to the chosen number");

    static {
        filter.add(NyotaUhuraCommunicationsPredicate.instance);
    }

    public NyotaUhuraCommunications(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{U}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.OFFICER);
        this.power = new MageInt(2);
        this.toughness = new MageInt(4);

        // Hailing Frequencies -- As Nyota Uhura enters, choose a number between 0 and 10.
        this.addAbility(new AsEntersBattlefieldAbility(new NyotaUhuraCommunicationsEffect()).withFlavorWord("Hailing Frequencies"));

        // Open a Channel -- Whenever an opponent casts a spell with mana value equal to the chosen number, you draw a card.
        this.addAbility(new SpellCastOpponentTriggeredAbility(
                new DrawCardSourceControllerEffect(1, true), filter, false
        ).withFlavorWord("Open a Channel"));
    }

    private NyotaUhuraCommunications(final NyotaUhuraCommunications card) {
        super(card);
    }

    @Override
    public NyotaUhuraCommunications copy() {
        return new NyotaUhuraCommunications(this);
    }

    static String key(UUID sourceId, int zcc) {
        return "chosenNumber_" + sourceId + '_' + zcc;
    }
}

enum NyotaUhuraCommunicationsPredicate implements ObjectSourcePlayerPredicate<StackObject> {
    instance;

    @Override
    public boolean apply(ObjectSourcePlayer<StackObject> input, Game game) {
        Object obj = game.getState().getValue(NyotaUhuraCommunications.key(
                input.getSource().getSourceId(), input.getSource().getStackMomentSourceZCC()));
        return obj instanceof Integer && input.getObject().getManaValue() == (Integer) obj;
    }
}

class NyotaUhuraCommunicationsEffect extends OneShotEffect {

    NyotaUhuraCommunicationsEffect() {
        super(Outcome.Benefit);
        staticText = "choose a number between 0 and 10";
    }

    private NyotaUhuraCommunicationsEffect(final NyotaUhuraCommunicationsEffect effect) {
        super(effect);
    }

    @Override
    public NyotaUhuraCommunicationsEffect copy() {
        return new NyotaUhuraCommunicationsEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return true;
        }
        int number = controller.getAmount(0, 10, "Choose a number.", source, game);
        game.getState().setValue(NyotaUhuraCommunications.key(source.getSourceId(), source.getStackMomentSourceZCC()), number);
        Permanent permanent = game.getPermanentEntering(source.getSourceId());
        if (permanent != null) {
            permanent.addInfo("chosen number", "<font color = 'blue'>Chosen Number: " + number + "</font>", game);
            game.informPlayers(permanent.getLogName() + ", chosen number: " + number);
        }
        return true;
    }
}
