tags:
	ctags -R src/ checkouts/

clean:
	rm -f *.aux *.bcf *.log *.blg *.bbl *.toc *.dvi *.fls *.fdb_latexmk \
	   *.run.xml acclivation.pdf

.PHONY: tags clean data
