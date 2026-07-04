namespace Mao.Domain.Entities;

/// <summary>Grande partie d'un métré (table <c>DIVISION</c>).</summary>
public class Division
{
    public int Id { get; set; }
    public int MetreId { get; set; }
    public Metre? Metre { get; set; }

    /// <summary>Numéro d'ordre d'affichage.</summary>
    public int Numero { get; set; }
    public string Intitule { get; set; } = string.Empty;
    public DateTime DerniereMaj { get; set; } = DateTime.Now;

    public List<Chapitre> Chapitres { get; set; } = new();
}
