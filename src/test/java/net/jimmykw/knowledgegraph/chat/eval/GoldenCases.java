package net.jimmykw.knowledgegraph.chat.eval;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public class GoldenCases {

    public static List<GoldenCase> all() {
        return List.of(
                new GoldenCase("What did IBM create?", null,
                        List.of("neighborhood-exploration"),
                        List.of("FORTRAN", "System/360", "SAGE", "Universal Product Code", "OS/2", "Selectric"),
                        30, false),
                new GoldenCase("What did IBM invent?", null,
                        List.of("neighborhood-exploration"),
                        List.of("FORTRAN", "magnetic stripe", "RISC architecture", "UPC"),
                        5, false),
                new GoldenCase("Tell me how Microsoft affected OS/2.", null,
                        List.of(),
                        List.of("Microsoft", "OS/2"),
                        1, false),
                new GoldenCase("Tell me any information about hard disks.", null,
                        List.of(),
                        List.of("hard disk", "disk"),
                        1, false),
                new GoldenCase("What did IBM create?",
                        "which of those were collaborations with other companies?",
                        List.of(),
                        List.of("NSFNet", "University of Michigan", "MCI", "Prodigy", "Sears"),
                        1, false),
                new GoldenCase("When did IBM acquire Google?", null,
                        List.of(),
                        List.of(),
                        0, true));
    }
}
