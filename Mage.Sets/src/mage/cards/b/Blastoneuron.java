package mage.cards.b;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.CardsLeaveGraveyardTriggeredAbility;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CreateTokenCopyTargetEffect;
import mage.abilities.effects.common.GainLifeEffect;
import mage.abilities.effects.common.LoseLifeOpponentsEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.HasteAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.filter.common.FilterCreatureCard;
import mage.filter.predicate.mageobject.ManaValuePredicate;
import mage.game.Game;
import mage.players.Player;
import mage.target.common.TargetCardInYourGraveyard;
import mage.target.targetpointer.FixedTarget;

import java.util.UUID;

public final class Blastoneuron extends CardImpl {

    private static final FilterCreatureCard filter = new FilterCreatureCard("creature card with mana value 3 or less from your graveyard");

    static {
        filter.add(new ManaValuePredicate(ComparisonType.FEWER_THAN, 4));
    }

    public Blastoneuron(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{3}{B}");

        this.subtype.add(SubType.JELLYFISH);
        this.subtype.add(SubType.LEECH);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // When this creature enters, exile target creature card with mana value 3 or less from your graveyard. Create a
        // token that's a copy of it. That token gains haste. Sacrifice it at the beginning of the next end step.
        Ability ability = new EntersBattlefieldTriggeredAbility(new BlastoneuronEffect());
        ability.addTarget(new TargetCardInYourGraveyard(filter));
        this.addAbility(ability);

        // Whenever one or more cards leave your graveyard, each opponent loses 1 life and you gain 1 life.
        Ability drain = new CardsLeaveGraveyardTriggeredAbility(new LoseLifeOpponentsEffect(1));
        drain.addEffect(new GainLifeEffect(1).concatBy("and"));
        this.addAbility(drain);
    }

    private Blastoneuron(final Blastoneuron card) {
        super(card);
    }

    @Override
    public Blastoneuron copy() {
        return new Blastoneuron(this);
    }
}

class BlastoneuronEffect extends OneShotEffect {

    BlastoneuronEffect() {
        super(Outcome.PutCreatureInPlay);
        staticText = "exile target creature card with mana value 3 or less from your graveyard. Create a token that's "
                + "a copy of it. That token gains haste. Sacrifice it at the beginning of the next end step";
    }

    private BlastoneuronEffect(final BlastoneuronEffect effect) {
        super(effect);
    }

    @Override
    public BlastoneuronEffect copy() {
        return new BlastoneuronEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Card card = game.getCard(getTargetPointer().getFirst(game, source));
        if (controller == null || card == null) {
            return false;
        }
        controller.moveCards(card, Zone.EXILED, source, game);
        CreateTokenCopyTargetEffect copy = new CreateTokenCopyTargetEffect(null, null, true);
        copy.setTargetPointer(new FixedTarget(card, game));
        copy.apply(game, source);
        copy.sacrificeTokensCreatedAtNextEndStep(game, source);
        return true;
    }
}
