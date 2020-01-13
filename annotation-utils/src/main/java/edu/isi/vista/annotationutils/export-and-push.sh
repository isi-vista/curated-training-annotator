#!/bin/bash

SMTPTO=curatedtraining@isi.edu
SERVERURL=curatedtraining.isi.edu
REPOURL=git@github.com:isi-vista/curated-training-annotation
THISDATE=$(date +"%Y-%m-%d")
STATSFILE="repos/curated-training-annotation/data/annotation-statistics/StatsReport$THISDATE.html"

sh ~/projects/gaia/repos/curated-training-annotator/annotation-utils/target/appassembler/bin/pushAnnotations "push_annotations.curatedtraining.params" > export-and-push.log 2>&1

grep 'ERROR\|Exception' -a export-and-push.log > export-and-push.error.log

if [ -s export-and-push.error.log ]; then
  RESULT="FAILURE"
elif grep -q 'Done!' -a export-and-push.log; then
  RESULT="SUCCESS"
  cat $STATSFILE >> export-and-push.error.log
else
  RESULT="INCOMPLETE"
  echo "Last 10 lines:" > export-and-push.error.log
  tail -n 10 export-and-push.log >> export-and-push.error.log
fi

mutt -e "set content_type=text/html" -s "$RESULT: Curated Training Upload from $SERVERURL to $REPOURL" $SMTPTO < export-and-push.error.log
