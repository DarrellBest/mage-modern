package mage.cards.s;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.CrewsAsThoughPowerTripledAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.util.CardUtil;

import java.util.UUID;

public final class SuluHeroicHelmsman extends CardImpl {

    public SuluHeroicHelmsman(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.PILOT);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Sulu stations Spacecraft and crews Vehicles as though his power were tripled.
        this.addAbility(new SimpleStaticAbility(new SuluHeroicHelmsmanStationEffect()));
        this.addAbility(new CrewsAsThoughPowerTripledAbility());
    }

    private SuluHeroicHelmsman(final SuluHeroicHelmsman card) {
        super(card);
    }

    @Override
    public SuluHeroicHelmsman copy() {
        return new SuluHeroicHelmsman(this);
    }
}

class SuluHeroicHelmsmanStationEffect extends ReplacementEffectImpl {

    SuluHeroicHelmsmanStationEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Benefit);
        staticText = "{this} stations Spacecraft and crews Vehicles as though his power were tripled";
    }

    private SuluHeroicHelmsmanStationEffect(final SuluHeroicHelmsmanStationEffect effect) {
        super(effect);
    }

    @Override
    public SuluHeroicHelmsmanStationEffect copy() {
        return new SuluHeroicHelmsmanStationEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.STATION_PERMANENT;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        return event.getTargetId().equals(source.getSourceId()); // Sulu is the creature being tapped to station
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        event.setAmount(CardUtil.overflowMultiply(event.getAmount(), 3));
        return false;
    }
}
