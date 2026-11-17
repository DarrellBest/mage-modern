package mage.cards.q;

import mage.MageInt;
import mage.abilities.triggers.BeginningOfUpkeepTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.continuous.CantBeSacrificedSourceEffect;
import mage.abilities.effects.common.continuous.GainAbilityControlledEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TargetController;
import mage.filter.FilterPermanent;
import mage.filter.predicate.permanent.TokenPredicate;
import mage.game.permanent.token.TreasureToken;

import java.util.UUID;

/**
 * You can only sacrifice permanents you control, so "you can't sacrifice Treasure tokens"
 * is implemented as Treasure tokens you control gaining "This token can't be sacrificed."
 * That blocks both sacrifice costs (a Treasure's own mana ability) and sacrifice effects.
 */
public final class QuarkHumbleProprietor extends CardImpl {

    private static final FilterPermanent filter = new FilterPermanent(SubType.TREASURE, "Treasure tokens");

    static {
        filter.add(TokenPredicate.TRUE);
    }

    public QuarkHumbleProprietor(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{R}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.FERENGI);
        this.subtype.add(SubType.ROGUE);
        this.power = new MageInt(1);
        this.toughness = new MageInt(2);

        // You can't sacrifice Treasure tokens.
        this.addAbility(new SimpleStaticAbility(new GainAbilityControlledEffect(
                new SimpleStaticAbility(new CantBeSacrificedSourceEffect()),
                Duration.WhileOnBattlefield, filter
        ).setText("you can't sacrifice Treasure tokens")));

        // At the beginning of each upkeep, create a Treasure token.
        this.addAbility(new BeginningOfUpkeepTriggeredAbility(
                TargetController.ANY, new CreateTokenEffect(new TreasureToken()), false
        ));
    }

    private QuarkHumbleProprietor(final QuarkHumbleProprietor card) {
        super(card);
    }

    @Override
    public QuarkHumbleProprietor copy() {
        return new QuarkHumbleProprietor(this);
    }
}
