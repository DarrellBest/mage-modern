package mage.cards.e;

import mage.MageInt;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.common.EntersBattlefieldAbility;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.CopyEffect;
import mage.abilities.keyword.FlashAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Outcome;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.common.FilterCreatureCard;
import mage.filter.predicate.card.PutIntoGraveFromBattlefieldThisTurnPredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.players.Player;
import mage.target.common.TargetCardInYourGraveyard;
import mage.util.CardUtil;
import mage.util.functions.CopyApplier;
import mage.watchers.common.CardsPutIntoGraveyardWatcher;

import java.util.UUID;

public final class EzriDaxNewlyJoined extends CardImpl {

    public EzriDaxNewlyJoined(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.TRILL);
        this.subtype.add(SubType.ADVISOR);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // Flash
        this.addAbility(FlashAbility.getInstance());

        // You may have Ezri Dax enter as a copy of any creature card in your graveyard that was put there from the
        // battlefield this turn, except her name is Ezri Dax, Newly Joined and she's a legendary Trill in addition to
        // her other types.
        Ability ability = new EntersBattlefieldAbility(new EzriDaxNewlyJoinedEffect(), true);
        ability.addWatcher(new CardsPutIntoGraveyardWatcher());
        this.addAbility(ability);
    }

    private EzriDaxNewlyJoined(final EzriDaxNewlyJoined card) {
        super(card);
    }

    @Override
    public EzriDaxNewlyJoined copy() {
        return new EzriDaxNewlyJoined(this);
    }
}

class EzriDaxNewlyJoinedEffect extends OneShotEffect {

    private static final FilterCreatureCard filter = new FilterCreatureCard(
            "creature card in your graveyard that was put there from the battlefield this turn");

    static {
        filter.add(PutIntoGraveFromBattlefieldThisTurnPredicate.instance);
    }

    EzriDaxNewlyJoinedEffect() {
        super(Outcome.Copy);
        staticText = "as a copy of any creature card in your graveyard that was put there from the battlefield this "
                + "turn, except her name is Ezri Dax, Newly Joined and she's a legendary Trill in addition to her other types";
    }

    private EzriDaxNewlyJoinedEffect(final EzriDaxNewlyJoinedEffect effect) {
        super(effect);
    }

    @Override
    public EzriDaxNewlyJoinedEffect copy() {
        return new EzriDaxNewlyJoinedEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        TargetCardInYourGraveyard target = new TargetCardInYourGraveyard(filter);
        target.withNotTarget(true);
        if (!target.canChoose(source.getControllerId(), source, game)) {
            return true;
        }
        player.choose(outcome, target, source, game);
        Card copyFromCard = game.getCard(target.getFirstTarget());
        if (copyFromCard == null) {
            return true;
        }
        Permanent blueprint = new PermanentCard(CardUtil.getDefaultCardSideForBattlefield(game, copyFromCard), source.getControllerId(), game);
        blueprint.assignNewId();
        new EzriDaxNewlyJoinedApplier().apply(game, blueprint, source, source.getSourceId());
        game.addEffect(new CopyEffect(Duration.Custom, blueprint, source.getSourceId()), source);
        return true;
    }
}

class EzriDaxNewlyJoinedApplier extends CopyApplier {

    @Override
    public boolean apply(Game game, MageObject blueprint, Ability source, UUID targetObjectId) {
        blueprint.setName("Ezri Dax, Newly Joined");
        blueprint.addSuperType(SuperType.LEGENDARY);
        blueprint.addSubType(SubType.TRILL);
        return true;
    }
}
