using System.Text;
using Mao.Domain.Entities;
using Mao.Domain.Services;

namespace Mao.Data;

/// <summary>Import / export de métrés au format d'échange « .mao ».</summary>
public class MaoFichierService
{
    private readonly MaoDbContext _ctx;

    public MaoFichierService(MaoDbContext ctx) => _ctx = ctx;

    /// <summary>Exporte un métré (chargé avec sa hiérarchie) vers un fichier .mao.</summary>
    public void Exporter(Metre metre, string chemin)
    {
        // Encodage Windows-1252 : celui attendu par MAO V8 (application ANSI).
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        File.WriteAllText(chemin, MaoFichierMetre.Ecrire(metre),
            Encoding.GetEncoding(1252));
    }

    /// <summary>Importe un fichier .mao comme nouveau métré. Retourne le métré créé.</summary>
    public Metre Importer(string chemin)
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        // Essaie UTF-8 (BOM) puis Windows-1252 (fichiers du programme d'origine).
        var octets = File.ReadAllBytes(chemin);
        string texte = octets.Length >= 3 && octets[0] == 0xEF && octets[1] == 0xBB && octets[2] == 0xBF
            ? Encoding.UTF8.GetString(octets, 3, octets.Length - 3)
            : Encoding.GetEncoding(1252).GetString(octets);

        var metre = MaoFichierMetre.Lire(texte.Split('\n').Select(l => l.TrimEnd('\r')));
        metre.DerniereMaj = DateTime.Now;
        _ctx.Metres.Add(metre);
        _ctx.SaveChanges();
        return metre;
    }
}
