package mb.statix.cli;

import static mb.statix.random.strategy.SearchStrategies.*;

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import mb.statix.constraints.CResolveQuery;
import mb.statix.constraints.CUser;
import mb.statix.random.FocusedSearchState;
import mb.statix.random.SearchState;
import mb.statix.random.SearchStrategy;
import mb.statix.random.predicate.Any;
import mb.statix.random.predicate.Match;
import mb.statix.random.predicate.Not;
import mb.statix.random.util.Either2;

public class Paret {

    public static SearchStrategy<SearchState, SearchState> enumerate() {
        // @formatter:off
        return seq(enumerateExp(),
               seq(generateLex(),
                   identity()));
        // @formatter:on
    }

    public static SearchStrategy<SearchState, SearchState> search() {
        // @formatter:off
        return seq(searchExp(),
               seq(generateLex(),
                   identity()));
        // @formatter:on
    }

    // generation of expressions

    private static SearchStrategy<SearchState, SearchState> inferDelayAndDrop() {
        return seq(infer(), seq(delayStuckQueries(), dropAst()));
    }

    // generation of expressions

    private static SearchStrategy<SearchState, SearchState> enumerateExp() {
        // @formatter:off
        return fix(
            seq(
                selectConstraint(1),
                match(
                    seq(resolve(), infer()),
                    seq(expand(), infer())
                )
            ),
            inferDelayAndDrop(),
            new Match("gen_.*")
        );
        // @formatter:on
    }

    private static SearchStrategy<SearchState, SearchState> searchExp() {
        // @formatter:off
        return repeat(limit(10, fix(
            seq( selectConstraint(1)
               , match(
                   limit(3, seq(            // find max 3 successful resolutions
                       limit(5, resolve()), // try at most 5 resolutions
                       infer()              // filter out choices that fail immediately
                   )),
                   limit(1, seq(                         // find max 1 successful rule expansion
                       limit(3, expand(1, ruleWeights)), // try at most 3 rules
                       infer()                           // filter out choices that fail immediately
                   ))
                 )
            ),
            inferDelayAndDrop(),
            new Match("gen_.*")
        )));
        // @formatter:on
    }

    // @formatter:off
    private static Map<String,Integer> ruleWeights = ImmutableMap.<String, Integer>builder()
        // TWEAK UnOp and BinOp get stuck often if they generate arguments before the operation
        .put("T-UnOp", 0)
        .put("T-BinOp", 0)
        // TWEAK Prefer rules that force types
        .put("T-Num", 2)
        .put("T-True", 2)
        .put("T-False", 2)
        .put("T-Nil", 2)
        .put("T-List", 2)
        .put("T-Fun", 2)
        .build();
    // @formatter:on

    public static SearchStrategy<SearchState, Either2<FocusedSearchState<CResolveQuery>, FocusedSearchState<CUser>>>
            selectConstraint(int limit) {
        // @formatter:off
        return limit(limit, concatAlt(
            // TWEAK Resolve queries first, to improve inference
            select(CResolveQuery.class, new Any<>()),
            select(CUser.class, new Not<>(new Match("gen_.*")))
        ));
        // @formatter:on
    }

    // generation of id's

    private static SearchStrategy<SearchState, SearchState> generateLex() {
        return require(limit(1, fix(expandLex(), infer(), new Not<>(new Match("gen_is.*")))));
    }

    private static SearchStrategy<SearchState, SearchState> expandLex() {
        // @formatter:off
        return seq(
            select(CUser.class, new Match("gen_is.*")),
            limit(1, seq(expand(0, idWeights), infer())) // TWEAK try one successful identifier only
        );
        // @formatter:on
    }

    // @formatter:off
    private static Map<String,Integer> idWeights = ImmutableMap.<String, Integer>builder()
        // TWEAK Increase likelihood of duplicate choices, while still providing many identifiers
        .put("[ID-A]", 16)
        .put("[ID-B]", 8)
        .put("[ID-C]", 8)
        .put("[ID-D]", 4)
        .put("[ID-E]", 4)
        .put("[ID-F]", 4)
        .put("[ID-G]", 4)
        .put("[ID-H]", 2)
        .put("[ID-I]", 2)
        .put("[ID-J]", 2)
        .put("[ID-K]", 2)
        .put("[ID-L]", 2)
        .put("[ID-M]", 2)
        .put("[ID-N]", 2)
        .put("[ID-O]", 2)
        .put("[ID-P]", 1)
        .put("[ID-Q]", 1)
        .put("[ID-R]", 1)
        .put("[ID-S]", 1)
        .put("[ID-T]", 1)
        .put("[ID-U]", 1)
        .put("[ID-V]", 1)
        .put("[ID-W]", 1)
        .put("[ID-X]", 1)
        .put("[ID-Y]", 1)
        .put("[ID-Z]", 1)
        .build();
    // @formatter:on

}