package edu.campusloop.exchange;

import edu.campusloop.common.ApiException;
import edu.campusloop.matching.IndependentMatchingInput;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExchangeCreationVersionBoundaryTest {
    @Test void creationMustLeaveAVersionIncrementForReleasingTheReservedItem() {
        for (int version : List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)) {
            ApiException error = assertThrows(ApiException.class,
                () -> new ExchangeCycleValidator().validate(101, command(version), input(version)));
            assertEquals(409, error.getStatus());
        }
    }

    @Test void lastVersionWithRoomForReservationAndReleaseRemainsUsable() {
        int version = Integer.MAX_VALUE - 2;
        var validated = new ExchangeCycleValidator().validate(101, command(version), input(version));
        assertEquals(version, validated.recommendation().participants().get(0).itemVersion());
    }

    @Test void selectedDemandMustHaveRoomForItsFulfillmentVersion() {
        for (int version : List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE)) {
            var base = input(0);
            var input = new IndependentMatchingInput(base.offers(), List.of(base.demands().get(0),
                new IndependentMatchingInput.Demand(502, 102, 1, Set.of(), Set.of(22L), "ACTIVE", version)));
            var command = new ExchangeCreationCommand("independent-v2", "demand-boundary", List.of(
                new ExchangeCreationCommand.ExpectedFlow(11, 0, 502, version),
                new ExchangeCreationCommand.ExpectedFlow(22, 0, 501, 0)));
            if (version == Integer.MAX_VALUE)
                assertEquals(409, assertThrows(ApiException.class,
                    () -> new ExchangeCycleValidator().validate(101, command, input)).getStatus());
            else assertDoesNotThrow(() -> new ExchangeCycleValidator().validate(101, command, input));
        }
    }

    private ExchangeCreationCommand command(int version) {
        return new ExchangeCreationCommand("independent-v2", "version-boundary", List.of(
            new ExchangeCreationCommand.ExpectedFlow(11, version, 502, 0),
            new ExchangeCreationCommand.ExpectedFlow(22, 0, 501, 0)));
    }

    private IndependentMatchingInput input(int version) {
        return new IndependentMatchingInput(List.of(
            new IndependentMatchingInput.Offer(11, 101, "虚构同学甲", "教材", 1, "图书", Set.of(), "AVAILABLE", "ACTIVE", false, version),
            new IndependentMatchingInput.Offer(22, 102, "虚构同学乙", "耳机", 2, "数码", Set.of(), "AVAILABLE", "ACTIVE", false, 0)), List.of(
            new IndependentMatchingInput.Demand(501, 101, 2, Set.of(), Set.of(11L), "ACTIVE", 0),
            new IndependentMatchingInput.Demand(502, 102, 1, Set.of(), Set.of(22L), "ACTIVE", 0)));
    }
}
