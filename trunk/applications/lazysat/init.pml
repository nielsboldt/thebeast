include "corpora/cora/cora.all.db.types.pml";
include "corpora/cora/cora.all.db.predicates.pml";

include "bibserv.factors.pml";

hidden: sameBib, sameAuthor, sameTitle, sameVenue;
observed: venue, title, author,
  titleScore100, titleScore80, titleScore60, titleScore40, titleScore20, titleScore0,
  authorScore100, authorScore80, authorScore60, authorScore40, authorScore20, authorScore0,
  venueScore100, venueScore80, venueScore60, venueScore40, venueScore20, venueScore0;

load corpus from "train.atoms";

load weights from "bibserv.weights";

save corpus to instances "/tmp/er.inst.dmp";

