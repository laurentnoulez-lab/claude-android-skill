using Mao.Domain.Entities;
using Mao.Domain.Services;

namespace Mao.Reports;

/// <summary>Construit un <see cref="DocumentMetre"/> à partir d'un métré et du moteur de calcul.</summary>
public class ReportBuilder
{
    private readonly MetreCalculator _calc;

    public ReportBuilder(MetreCalculator calc) => _calc = calc;

    public DocumentMetre Construire(Metre metre, TypeDocument type)
    {
        var divisions = new List<SectionDivision>();

        foreach (var div in metre.Divisions.OrderBy(d => d.Numero))
        {
            var chapitres = new List<SectionChapitre>();
            foreach (var chap in div.Chapitres.OrderBy(c => c.Numero))
            {
                var lignes = new List<LignePoste>();
                foreach (var poste in chap.Postes.OrderBy(p => p.Numero))
                {
                    var t = _calc.Calculer(metre, poste);
                    lignes.Add(new LignePoste(
                        poste.Numero, poste.CodePosteStd, poste.Intitule, poste.Unite,
                        poste.QuantitePresumee, poste.PrixUnitaire, t.Htva, t.Tva, t.Ttc));
                }
                var tc = _calc.Calculer(metre, chap);
                chapitres.Add(new SectionChapitre(chap.Intitule, lignes, tc.Htva, tc.Tva, tc.Ttc));
            }
            var td = _calc.Calculer(metre, div);
            divisions.Add(new SectionDivision(div.Intitule, chapitres, td.Htva, td.Tva, td.Ttc));
        }

        var total = _calc.Calculer(metre);
        return new DocumentMetre(
            type, metre.Intitule, metre.ListeNormalisee, DateTime.Now,
            divisions, total.Htva, total.Tva, total.Ttc);
    }
}
