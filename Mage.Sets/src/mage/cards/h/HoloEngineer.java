package mage.cards.h;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldTriggeredAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.game.Game;
import mage.game.events.CreateTokenEvent;
import mage.game.events.GameEvent;
import mage.game.permanent.token.IllusionArtifactToken;
import mage.game.permanent.token.Token;

import java.util.Map;
import java.util.UUID;

public final class HoloEngineer extends CardImpl {

    public HoloEngineer(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{2}{G}{G}");

        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.ARTIFICER);
        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        // When this creature enters, create a 3/3 colorless Illusion artifact creature token.
        this.addAbility(new EntersBattlefieldTriggeredAbility(new CreateTokenEffect(new IllusionArtifactToken())));

        // If one or more tokens would be created under your control, that many 3/3 colorless Illusion artifact creature tokens are created instead.
        this.addAbility(new SimpleStaticAbility(new HoloEngineerEffect()));
    }

    private HoloEngineer(final HoloEngineer card) {
        super(card);
    }

    @Override
    public HoloEngineer copy() {
        return new HoloEngineer(this);
    }
}

class HoloEngineerEffect extends ReplacementEffectImpl {

    HoloEngineerEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Benefit, false);
        staticText = "If one or more tokens would be created under your control, that many 3/3 colorless Illusion "
                + "artifact creature tokens are created instead.";
    }

    private HoloEngineerEffect(final HoloEngineerEffect effect) {
        super(effect);
    }

    @Override
    public HoloEngineerEffect copy() {
        return new HoloEngineerEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.CREATE_TOKEN;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        if (!(event instanceof CreateTokenEvent) || !source.isControlledBy(event.getPlayerId())) {
            return false;
        }
        // already all Illusions: nothing to replace (also stops it re-applying to its own result)
        return ((CreateTokenEvent) event).getTokens().keySet().stream()
                .anyMatch(token -> !(token instanceof IllusionArtifactToken));
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        CreateTokenEvent tokenEvent = (CreateTokenEvent) event;
        int amount = 0;
        for (Map.Entry<Token, Integer> entry : tokenEvent.getTokens().entrySet()) {
            amount += entry.getValue();
        }
        tokenEvent.getTokens().clear();
        if (amount > 0) {
            tokenEvent.getTokens().put(new IllusionArtifactToken(), amount);
        }
        return false;
    }
}
