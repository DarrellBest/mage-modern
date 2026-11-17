package mage.cards.t;

import mage.abilities.common.SagaAbility;
import mage.abilities.effects.Effects;
import mage.abilities.effects.common.DrawDiscardControllerEffect;
import mage.abilities.effects.common.ReturnFromGraveyardToHandTargetEffect;
import mage.abilities.effects.common.ShuffleIntoLibraryTargetEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.SagaChapter;
import mage.constants.SubType;
import mage.filter.FilterCard;
import mage.filter.predicate.Predicates;
import mage.target.common.TargetCardInGraveyard;
import mage.target.common.TargetCardInYourGraveyard;

import java.util.UUID;

public final class ThoseOldScientists extends CardImpl {

    private static final FilterCard filter = new FilterCard("artifact, instant, or sorcery card from your graveyard");

    static {
        filter.add(Predicates.or(
                CardType.ARTIFACT.getPredicate(), CardType.INSTANT.getPredicate(), CardType.SORCERY.getPredicate()
        ));
    }

    public ThoseOldScientists(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ENCHANTMENT}, "{2}{U}");

        this.subtype.add(SubType.SAGA);

        // (As this Saga enters and after your draw step, add a lore counter. Sacrifice after III.)
        SagaAbility sagaAbility = new SagaAbility(this);

        // I -- Draw two cards, then discard two cards.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_I, new DrawDiscardControllerEffect(2, 2));

        // II -- Return up to one target artifact, instant, or sorcery card from your graveyard to your hand.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_II,
                new Effects(new ReturnFromGraveyardToHandTargetEffect()), new TargetCardInYourGraveyard(0, 1, filter));

        // III -- Choose up to two target cards in graveyards. The owners of those cards shuffle them into their libraries.
        sagaAbility.addChapterEffect(this, SagaChapter.CHAPTER_III,
                new Effects(new ShuffleIntoLibraryTargetEffect().setText(
                        "choose up to two target cards in graveyards. The owners of those cards shuffle them into their libraries")),
                new TargetCardInGraveyard(0, 2));

        this.addAbility(sagaAbility);
    }

    private ThoseOldScientists(final ThoseOldScientists card) {
        super(card);
    }

    @Override
    public ThoseOldScientists copy() {
        return new ThoseOldScientists(this);
    }
}
