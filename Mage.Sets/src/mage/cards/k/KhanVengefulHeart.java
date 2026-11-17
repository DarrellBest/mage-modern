package mage.cards.k;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.costs.common.SacrificeTargetCost;
import mage.abilities.effects.AsThoughEffectImpl;
import mage.abilities.effects.common.DoIfCostPaid;
import mage.abilities.effects.common.DrawCardSourceControllerEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.AsThoughEffectType;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.Zone;
import mage.filter.common.FilterControlledPermanent;
import mage.filter.predicate.Predicates;
import mage.filter.predicate.mageobject.AnotherPredicate;
import mage.game.Game;

import java.util.UUID;

public final class KhanVengefulHeart extends CardImpl {

    private static final FilterControlledPermanent filter = new FilterControlledPermanent("another creature or artifact");

    static {
        filter.add(AnotherPredicate.instance);
        filter.add(Predicates.or(CardType.CREATURE.getPredicate(), CardType.ARTIFACT.getPredicate()));
    }

    public KhanVengefulHeart(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{B}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.WARRIOR);
        this.power = new MageInt(5);
        this.toughness = new MageInt(3);

        // When Khan enters, you may sacrifice another creature or artifact. If you do, draw two cards.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new DoIfCostPaid(
                new DrawCardSourceControllerEffect(2), new SacrificeTargetCost(filter)
        )));

        // You may cast this card from exile.
        this.addAbility(new SimpleStaticAbility(Zone.EXILED, new KhanVengefulHeartCastEffect()));
    }

    private KhanVengefulHeart(final KhanVengefulHeart card) {
        super(card);
    }

    @Override
    public KhanVengefulHeart copy() {
        return new KhanVengefulHeart(this);
    }
}

class KhanVengefulHeartCastEffect extends AsThoughEffectImpl {

    KhanVengefulHeartCastEffect() {
        super(AsThoughEffectType.CAST_FROM_NOT_OWN_HAND_ZONE, Duration.EndOfGame, Outcome.Benefit);
        staticText = "you may cast this card from exile";
    }

    private KhanVengefulHeartCastEffect(final KhanVengefulHeartCastEffect effect) {
        super(effect);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        return true;
    }

    @Override
    public KhanVengefulHeartCastEffect copy() {
        return new KhanVengefulHeartCastEffect(this);
    }

    @Override
    public boolean applies(UUID sourceId, Ability source, UUID affectedControllerId, Game game) {
        if (!sourceId.equals(source.getSourceId())) {
            return false;
        }
        Card card = game.getCard(source.getSourceId());
        return card != null && card.isOwnedBy(affectedControllerId)
                && game.getState().getZone(source.getSourceId()) == Zone.EXILED;
    }
}
